//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.util.LinkedHashMap;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.appwork.uio.ConfirmDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.StringUtils;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.jdownloader.controlling.ffmpeg.json.StreamInfo;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.downloader.hls.M3U8Playlist;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.scripting.JavaScriptEngineFactory;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "twitter.com" }, urls = { "https?://[a-z0-9]+\\.twimg\\.com/media/[^/]+|https?://amp\\.twimg\\.com/prod/[^<>\"]*?/vmap/[^<>\"]*?\\.vmap|https?://amp\\.twimg\\.com/v/.+|https?://(?:www\\.)?twitter\\.com/i/videos/tweet/\\d+" })
public class TwitterCom extends PluginForHost {
    public TwitterCom(PluginWrapper wrapper) {
        super(wrapper);
        /* 2020-01-20: Disabled login functionality as it is broken */
        this.enablePremium("https://twitter.com/signup");
    }

    @Override
    public String getAGBLink() {
        return "https://twitter.com/tos";
    }

    @Override
    public boolean allowHandle(final DownloadLink link, final PluginForHost plugin) {
        return link.getHost().equalsIgnoreCase(plugin.getHost());
    }

    private final String       TYPE_DIRECT                  = "https?://[a-z0-9]+\\.twimg\\.com/.+";
    private final String       TYPE_VIDEO                   = "https?://amp\\.twimg\\.com/v/.+";
    private final String       TYPE_VIDEO_VMAP              = "https?://amp\\.twimg\\.com/prod/[^<>\"]*?/vmap/[^<>\"]*?\\.vmap";
    public static final String TYPE_VIDEO_EMBED             = "https?://(?:www\\.)?twitter\\.com/i/videos/tweet/\\d+";
    /* Connection stuff - don't allow chunks as we only download small pictures */
    private final boolean      FREE_RESUME                  = true;
    private final int          FREE_MAXCHUNKS               = 1;
    private final int          FREE_MAXDOWNLOADS            = 20;
    private final boolean      ACCOUNT_FREE_RESUME          = true;
    private final int          ACCOUNT_FREE_MAXCHUNKS       = 1;
    private final int          ACCOUNT_FREE_MAXDOWNLOADS    = 20;
    private String             dllink                       = null;
    private boolean            account_required             = false;
    private boolean            geo_blocked                  = false;
    private boolean            server_issues                = false;
    private String             tweetid                      = null;
    private String             guest_token                  = null;
    public static final String COOKIE_KEY_LOGINED_CSRFTOKEN = "ct0";

    public static Browser prepBR(final Browser br) {
        br.setAllowedResponseCodes(new int[] { 429 });
        return br;
    }

    private void setconstants(final DownloadLink link) {
        dllink = null;
        server_issues = false;
        account_required = false;
        if (link.getDownloadURL().matches(TYPE_VIDEO_EMBED)) {
            tweetid = new Regex(link.getDownloadURL(), "(\\d+)$").getMatch(0);
        } else {
            tweetid = link.getStringProperty("tweetid", null);
        }
    }

    private static Object LOCK = new Object();

    /** 2017-11-29: TODO: For videos: Check this way https://api.twitter.com/1.1/videos/tweet/config/<tweetid>.json */
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, null, false);
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        setconstants(link);
        prepBR(this.br);
        URLConnectionAdapter con = null;
        String title = null;
        String description = null;
        /* Most times twitter-image/videolinks will come from the decrypter. */
        String filename = link.getStringProperty("decryptedfilename", null);
        String vmap_url = null;
        boolean possibly_geo_blocked = false;
        if (link.getPluginPatternMatcher().matches(TYPE_VIDEO) || link.getPluginPatternMatcher().matches(TYPE_VIDEO_VMAP)) {
            this.br.getPage(link.getDownloadURL());
            if (this.br.getHttpConnection().getResponseCode() == 403) {
                account_required = true;
                return AvailableStatus.TRUE;
            } else if (this.br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (link.getPluginPatternMatcher().matches(TYPE_VIDEO_VMAP)) {
                /* Direct vmap url was added by user- or decrypter. */
                vmap_url = link.getPluginPatternMatcher();
            } else {
                /* Videolink was added by user or decrypter. */
                vmap_url = this.br.getRegex("name=\"twitter:amplify:vmap\" content=\"(https?://[^<>\"]*?\\.vmap)\"").getMatch(0);
            }
            if (StringUtils.isEmpty(vmap_url)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            this.br.getPage(vmap_url);
            this.dllink = regexVideoVmapHighestQualityURL(this.br);
            if (StringUtils.isEmpty(dllink)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        } else if (link.getPluginPatternMatcher().matches(TYPE_VIDEO_EMBED)) {
            final String tweet_id = new Regex(link.getPluginPatternMatcher(), "/tweet/(\\d+)$").getMatch(0);
            /* 2018-11-13: Using static token */
            final boolean use_static_token = true;
            final String authorization_token;
            if (use_static_token) {
                authorization_token = "AAAAAAAAAAAAAAAAAAAAAIK1zgAAAAAA2tUWuhGZ2JceoId5GwYWU5GspY4%3DUq7gzFoCZs1QfwGoVdvSac3IniczZEYXIcDyumCauIXpcAPorE";
            } else {
                br.getPage(link.getPluginPatternMatcher());
                if (this.br.getHttpConnection().getResponseCode() == 403) {
                    account_required = true;
                    return AvailableStatus.TRUE;
                } else if (this.br.getHttpConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                final String jsURL = br.getRegex("<script src=\"(https?://[^\"]+/TwitterVideoPlayerIframe[^\"]+\\.js)\">").getMatch(0);
                if (jsURL == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                br.getPage(jsURL);
                authorization_token = br.getRegex("Authorization:\"Bearer ([^\"]+)\"").getMatch(0);
                if (authorization_token == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            br.getHeaders().put("Authorization", "Bearer " + authorization_token);
            br.getHeaders().put("Accept", "*/*");
            br.getHeaders().put("Origin", "https://twitter.com");
            br.getHeaders().put("Referer", "https://" + this.getHost() + "/i/videos/tweet/" + tweet_id);
            synchronized (LOCK) {
                jd.plugins.decrypter.TwitterCom.prepAPIHeaders(br);
                /* Set guest_token header if needed. */
                if (account == null) {
                    if (guest_token == null) {
                        /** TODO: Save guest_token throughout session so we do not generate them so frequently */
                        guest_token = jd.plugins.decrypter.TwitterCom.generateNewGuestToken(br);
                    }
                    if (guest_token != null) {
                        br.getHeaders().put("x-guest-token", guest_token);
                    } else {
                        logger.warning("Failed to get guesttoken");
                    }
                }
                /*
                 * Without guest_token in header we might often get blocked here with this response: HTTP/1.1 429 Too Many Requests -->
                 * {"errors":[{"message":"Rate limit exceeded","code":88}]}
                 */
                br.getPage("https://api.twitter.com/1.1/videos/tweet/config/" + tweet_id + ".json");
                if (br.getHttpConnection().getResponseCode() == 429) {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Rate-limit reached", 5 * 60 * 1000l);
                }
                try {
                    LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaMap(br.toString());
                    entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.walkJson(entries, "track/mediaAvailability");
                    final String status = (String) entries.get("status");
                    if (status.equalsIgnoreCase("unavailable")) {
                        final String reason = (String) entries.get("reason");
                        if (reason.equalsIgnoreCase("geoblocked")) {
                            possibly_geo_blocked = true;
                        }
                    }
                } catch (final Throwable e) {
                }
                final String errorcode = PluginJSonUtils.getJson(br, "error");
                final String errormessage = PluginJSonUtils.getJson(br, "message");
                if (br.containsHTML("<div id=\"message\">")) {
                    /* E.g. <div id="message">Das Medium konnte nicht abgespielt werden. */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else if (!StringUtils.isEmpty(errorcode)) {
                    logger.info("Failure, errorcode: " + errorcode);
                    if (!StringUtils.isEmpty(errormessage)) {
                        logger.info("Errormessage: " + errormessage);
                    }
                    if (errorcode.equals("239")) {
                        /*
                         * 2019-08-20: {"errors":[{"code":239,"message":"Bad guest token."}]}
                         */
                        logger.info("Possible token failure 239, retrying");
                        guest_token = null;
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 353", 3 * 60 * 1000l);
                    } else if (errorcode.equals("353")) {
                        logger.info("Possible token failure 353, retrying");
                        guest_token = null;
                        throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error 353", 2 * 60 * 1000l);
                    } else {
                        logger.warning("Unknown error");
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown API error " + errorcode);
                    }
                } else if (br.getHttpConnection().getResponseCode() == 403) {
                    /* 403 is typically 'rights missing' but in this case it means that the content is offline. */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            }
            // this.br.getRequest().setHtmlCode(Encoding.htmlDecode(this.br.toString()));
            dllink = PluginJSonUtils.getJson(this.br, "playbackUrl");
            if (StringUtils.isEmpty(dllink)) {
                final LinkedHashMap<String, Object> entries = jd.plugins.decrypter.TwitterCom.getPlayerData(br);
                final LinkedHashMap<String, Object> videoInfo = (LinkedHashMap<String, Object>) entries.get("videoInfo");
                title = (String) videoInfo.get("title");
                description = (String) videoInfo.get("description");
                vmap_url = (String) entries.get("vmap_url");
                if (StringUtils.isEmpty(vmap_url)) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                br.getPage(vmap_url);
                this.dllink = regexVideoVmapHighestQualityURL(this.br);
            }
            if (!StringUtils.isEmpty(title)) {
                filename = tweetid + "_" + title + ".mp4";
            } else {
                filename = tweetid + "_" + tweetid + ".mp4";
            }
        } else { // TYPE_DIRECT - jpg/png/mp4
            dllink = link.getDownloadURL();
            if (dllink.contains("jpg") || dllink.contains("png")) {
                try {
                    final String dllink_temp;
                    if (dllink.contains(":large")) {
                        dllink_temp = dllink.replace(":large", "") + ":orig";
                    } else if (dllink.lastIndexOf(":") < 8 && dllink.matches(".+\\.(jpg|jpeg|png)$")) {
                        /* Append this to get the highest quality possible */
                        dllink_temp = dllink + ":orig";
                    } else {
                        dllink_temp = dllink;
                    }
                    con = br.openHeadConnection(dllink_temp);
                    if (!con.getContentType().contains("html")) {
                        dllink = dllink_temp;
                        link.setUrlDownload(dllink);
                    }
                } finally {
                    con.disconnect();
                }
            }
        }
        if (!StringUtils.isEmpty(dllink)) {
            try {
                if (dllink.contains(".m3u8")) {
                    link.setFinalFileName(filename);
                    checkFFProbe(link, "Download a HLS Stream");
                    br.setAllowedResponseCodes(new int[] { 403 });
                    try {
                        br.getPage(dllink);
                    } catch (final Exception e) {
                        logger.info("Fatal failure");
                    }
                    if (this.br.getHttpConnection().getResponseCode() == 403) {
                        /* 2017-06-01: Unsure because browser shows the thumbnail and video 'wants to play' but doesn't. */
                        // throw new PluginException(LinkStatus.ERROR_FATAL, "GEO-blocked or offline content");
                        if (possibly_geo_blocked) {
                            /* We already had the info before that this content is probably GEO-blocked - now we know it for sure! */
                            geo_blocked = true;
                        } else {
                            account_required = true;
                        }
                        return AvailableStatus.TRUE;
                    } else if (br.getHttpConnection().getResponseCode() == 404) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                    final HlsContainer hlsBest = HlsContainer.findBestVideoByBandwidth(HlsContainer.getHlsQualities(this.br));
                    this.dllink = hlsBest.getDownloadurl();
                    final HLSDownloader downloader = new HLSDownloader(link, br, dllink);
                    final StreamInfo streamInfo = downloader.getProbe();
                    if (streamInfo == null) {
                        // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                        server_issues = true;
                    } else {
                        final int hlsBandwidth = hlsBest.getBandwidth();
                        if (hlsBandwidth > 0) {
                            for (M3U8Playlist playList : downloader.getPlayLists()) {
                                playList.setAverageBandwidth(hlsBandwidth);
                            }
                        }
                        final long estimatedSize = downloader.getEstimatedSize();
                        if (estimatedSize > 0) {
                            link.setDownloadSize(estimatedSize);
                        }
                    }
                } else {
                    con = br.openHeadConnection(dllink);
                    if (con.getResponseCode() == 404) {
                        /* Definitly offline */
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                    final long filesize = con.getLongContentLength();
                    if (filesize == 0) {
                        /* 2017-07-18: E.g. abused video OR temporarily unavailable picture */
                        // throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server sent empty file", 60 * 1000l);
                        // 2017-07-20: Pass it to download core, it can handle this.
                        logger.info("Downloading empty file ...");
                    }
                    if (!con.getContentType().contains("html") && con.isOK() && con.getLongContentLength() >= 0) {
                        if (filename == null) {
                            filename = Encoding.htmlDecode(getFileNameFromHeader(con)).replace(":orig", "");
                        }
                        if (tweetid != null && !filename.contains(tweetid)) {
                            filename = tweetid + "_" + filename;
                        }
                        link.setFinalFileName(filename);
                        link.setDownloadSize(filesize);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        if (!StringUtils.isEmpty(description) && link.getComment() == null) {
            link.setComment(description);
        }
        return AvailableStatus.TRUE;
    }

    private static String regexVideoVmapHighestQualityURL(final Browser br) {
        String videourl = br.getRegex("<MediaFile>\\s*?<\\!\\[CDATA\\[(http[^<>\"]*?)\\]\\]>\\s*?</MediaFile>").getMatch(0);
        if (videourl == null) {
            /* HLS */
            videourl = br.getRegex("<MediaFile type=\"application/x-mpegURL\">\\s*?<\\!\\[CDATA\\[(http[^<>\"]*?)\\]\\]>\\s*?</MediaFile>").getMatch(0);
        }
        return videourl;
    }

    public static String regexTwitterVideo(final String source) {
        String finallink = PluginJSonUtils.getJson(source, "video_url");
        // String finallink = new Regex(source, "video_url\\&quot;:\\&quot;(https:[^<>\"]*?\\.mp4)\\&").getMatch(0);
        // if (finallink != null) {
        // finallink = finallink.replace("\\", "");
        // }
        return finallink;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link, null, true);
        doFree(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink downloadLink, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        if (geo_blocked) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "GEO-blocked");
        } else if (account_required) {
            /*
             * 2017-05-10: This can also happen when a user is logged in because there are e.g. timelines which only 'friends' can view
             * which means having an account does not necessarily mean that a user has the rights to view all of the other users' content ;)
             */
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        } else if (StringUtils.isEmpty(dllink)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (this.dllink.contains(".m3u8")) {
            dl = new HLSDownloader(downloadLink, br, this.dllink);
            dl.startDownload();
        } else {
            dl = new jd.plugins.BrowserAdapter().openDownload(br, downloadLink, dllink, resumable, maxchunks);
            if (dl.getConnection().getContentType().contains("html")) {
                br.followConnection();
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl.startDownload();
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    public static void login(final Browser br, final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                br.setFollowRedirects(true);
                if (cookies != null) {
                    /*
                     * Re-use cookies whenever possible as frequent logins will cause accounts to get blocked and owners will get warnings
                     * via E-Mail
                     */
                    br.setCookies(account.getHoster(), cookies);
                    // br.getHeaders().put("Referer", "https://twitter.com/");
                    br.getPage("https://" + account.getHoster() + "/");
                    final String auth_cookie = br.getCookie(br.getHost(), "auth_token", Cookies.NOTDELETEDPATTERN);
                    if (auth_cookie != null) {
                        /* Set new cookie timestamp */
                        br.setCookies(account.getHoster(), cookies);
                        return;
                    }
                    /* Force full login (or login with user given cookies) */
                }
                /* 2020-07-02: Only cookie login is supported! */
                final boolean allowCookieLoginOnly = true;
                final Cookies userCookies = Cookies.parseCookiesFromJsonString(account.getPass());
                if (userCookies != null && !userCookies.isEmpty()) {
                    /* 2020-02-13: Experimental - accepts cookies exported via browser addon "EditThisCookie" */
                    br.setCookies(userCookies);
                    jd.plugins.decrypter.TwitterCom.prepAPIHeaders(br);
                    br.getPage("https://api.twitter.com/2/badge_count/badge_count.json?supports_ntab_urt=1");
                    if (br.getRequest().getHttpConnection().getResponseCode() != 200) {
                        showCookieLoginInformation();
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                } else {
                    if (allowCookieLoginOnly) {
                        showCookieLoginInformation();
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "Enter cookies to login", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                    br.getPage("https://" + account.getHoster() + "/login");
                    String authenticytoken = br.getRegex("type=\"hidden\" value=\"([^<>\"]*?)\" name=\"authenticity_token\"").getMatch(0);
                    if (authenticytoken == null) {
                        authenticytoken = br.getCookie(br.getHost(), "_mb_tk", Cookies.NOTDELETEDPATTERN);
                    }
                    if (authenticytoken == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    final String postData = "session%5Busername_or_email%5D=" + Encoding.urlEncode(account.getUser()) + "&session%5Bpassword%5D=" + Encoding.urlEncode(account.getPass()) + "&return_to_ssl=true&authenticity_token=" + Encoding.urlEncode(authenticytoken) + "&scribe_log=&redirect_after_login=&authenticity_token=" + Encoding.urlEncode(authenticytoken) + "&remember_me=1&ui_metrics=" + Encoding.urlEncode("{\"rf\":{\"\":208,\"\":-17,\"\":-29,\"\":-18},\"s\":\"\"}");
                    br.postPage("/sessions", postData);
                    if (br.getCookie(br.getHost(), "auth_token", Cookies.NOTDELETEDPATTERN) == null) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(br.getCookies(br.getHost()), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    private static Thread showCookieLoginInformation() {
        final Thread thread = new Thread() {
            public void run() {
                try {
                    final String help_article_url = "https://support.jdownloader.org/Knowledgebase/Article/View/account-cookie-login-instructions";
                    String message = "";
                    final String title;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        title = "Twitter - Login";
                        message += "Hallo liebe(r) Twitter NutzerIn\r\n";
                        message += "Um deinen Twitter Account in JDownloader verwenden zu können, musst du folgende Schritte beachten:\r\n";
                        message += "Folge der Anleitung im Hilfe-Artikel:\r\n";
                        message += help_article_url;
                    } else {
                        title = "Twitter - Login";
                        message += "Hello dear Twitter user\r\n";
                        message += "In order to use an account of this service in JDownloader, you need to follow these instructions:\r\n";
                        message += help_article_url;
                    }
                    final ConfirmDialog dialog = new ConfirmDialog(UIOManager.LOGIC_COUNTDOWN, title, message);
                    dialog.setTimeout(3 * 60 * 1000);
                    if (CrossSystem.isOpenBrowserSupported() && !Application.isHeadless()) {
                        CrossSystem.openURL(help_article_url);
                    }
                    final ConfirmDialogInterface ret = UIOManager.I().show(ConfirmDialogInterface.class, dialog);
                    ret.throwCloseExceptions();
                } catch (final Throwable e) {
                    // getLogger().log(e);
                }
            };
        };
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        try {
            login(this.br, account, true);
        } catch (PluginException e) {
            throw e;
        }
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        account.setMaxSimultanDownloads(1);
        account.setConcurrentUsePossible(true);
        ai.setStatus("Free Account");
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        login(this.br, account, false);
        requestFileInformation(link, account, true);
        doFree(link, ACCOUNT_FREE_RESUME, ACCOUNT_FREE_MAXCHUNKS, "account_free_directlink");
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return TwitterConfigInterface.class;
    }

    public static interface TwitterConfigInterface extends PluginConfigInterface {
        public static final TRANSLATION TRANSLATION = new TRANSLATION();

        public static class TRANSLATION {
            public String getForceGrabMediaOnlyEnabled_label() {
                return "Force grab media? Disable this to also crawl media of retweets and other content from users' timelines (only if you add URLs without '/media'!)";
            }
        }

        @DefaultBooleanValue(true)
        @AboutConfig
        boolean isForceGrabMediaOnlyEnabled();

        void setForceGrabMediaOnlyEnabled(boolean b);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}