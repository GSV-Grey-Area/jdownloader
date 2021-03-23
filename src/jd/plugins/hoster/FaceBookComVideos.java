//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.uio.ConfirmDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.DebugMode;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.jdownloader.captcha.v2.challenge.recaptcha.v1.Recaptcha;
import org.jdownloader.plugins.components.config.FacebookConfig;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "facebook.com" }, urls = { "https?://(?:[a-z0-9\\-]+\\.)?facebook\\.com/(?:.*?video\\.php\\?v=|photo\\.php\\?fbid=|video/embed\\?video_id=|.*?/videos/(?:[^/]+/)?|watch/\\?v=|watch/live/\\?v=)(\\d+)" })
public class FaceBookComVideos extends PluginForHost {
    /* 2020-06-05: TODO: This linktype can (also) lead to video content! */
    private static final String TYPE_PHOTO                             = "(?i)https?://[^/]+/photo\\.php\\?fbid=\\d+";
    private static final String TYPE_VIDEO_WATCH                       = "(?i)https?://[^/]+/watch/\\?v=(\\d+)";
    private static final String TYPE_VIDEO_WITH_UPLOADER_NAME          = "(?i)https://[^/]+/([^/]+)/videos/(\\d+).*";
    // private static final String TYPE_SINGLE_VIDEO_ALL = "https?://(www\\.)?facebook\\.com/video\\.php\\?v=\\d+";
    private static final long   trust_cookie_age                       = 300000l;
    private int                 maxChunks                              = 0;
    private static final String PROPERTY_DATE_FORMATTED                = "date_formatted";
    private static final String PROPERTY_TITLE                         = "title";
    private static final String PROPERTY_UPLOADER                      = "uploader";
    private static final String PROPERTY_UPLOADER_URL                  = "uploader_url";
    private static final String PROPERTY_DIRECTURL                     = "directurl";
    private static final String PROPERTY_IS_CHECKABLE_VIA_PLUGIN_EMBED = "is_checkable_via_plugin_embed";
    private static final String PROPERTY_ACCOUNT_REQUIRED              = "account_required";
    private static final String PROPERTY_RUNTIME_MILLISECONDS          = "runtime_milliseconds";

    public FaceBookComVideos(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.facebook.com/r.php");
        /*
         * to prevent all downloads starting and finishing together (quite common with small image downloads), login, http request and json
         * task all happen at same time and cause small hangups and slower download speeds. raztoki20160309
         */
        setStartIntervall(200l);
    }

    @Override
    public void correctDownloadLink(final DownloadLink link) throws Exception {
        /* 2021-03-22: E.g. remove mobile page subdomain. */
        final String domain = new Regex(link.getPluginPatternMatcher(), "(?i)https?://([^/]+)/.*").getMatch(0);
        link.setPluginPatternMatcher(link.getPluginPatternMatcher().replaceFirst("(?i)" + Pattern.quote(domain), "www.facebook.com"));
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    public boolean isProxyRotationEnabledForLinkChecker() {
        return false;
    }

    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, null, false);
    }

    private Browser prepBR(final Browser br) {
        br.getHeaders().put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        br.getHeaders().put("Accept-Language", "en-gb, en;q=0.9");
        br.getHeaders().put("Accept-Encoding", "gzip, deflate");
        br.getHeaders().put("Accept-Charset", "ISO-8859-1,utf-8;q=0.7,*;q=0.7");
        br.setCookie("http://www.facebook.com", "locale", "en_GB");
        br.setFollowRedirects(true);
        return br;
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, Account account, final boolean isDownload) throws Exception {
        if (link.getPluginPatternMatcher().matches(TYPE_PHOTO)) {
            return this.requestFileInformationPhoto(link, isDownload);
        } else {
            if (!link.isNameSet()) {
                link.setName(this.getFID(link) + ".mp4");
            }
            prepBR(this.br);
            String dllink = link.getStringProperty(PROPERTY_DIRECTURL);
            if (dllink != null) {
                if (this.checkDirecturlAndSetFilesize(link, dllink)) {
                    logger.info("Availablecheck only via directurl done");
                    return AvailableStatus.TRUE;
                } else {
                    logger.info("Availablecheck via saved directurl failed -> Doing full availablecheck");
                }
            }
            if (account == null) {
                /* Try to get ANY account */
                account = AccountController.getInstance().getValidAccount(this.getHost());
            }
            if (account != null) {
                login(account, this.br, false);
            }
            final boolean fastLinkcheck = PluginJsonConfig.get(this.getConfigInterface()).isEnableFastLinkcheck();
            final boolean findAndCheckDownloadurl = !fastLinkcheck || isDownload;
            /* Embed only = no nice filenames given */
            final boolean useEmbedOnly = false;
            if (useEmbedOnly) {
                this.requestFileInformationEmbed(link, isDownload);
            } else {
                /*
                 * First round = do this as this is the best way to find all required filename information especially the upload-date!
                 */
                AvailableStatus websiteCheckResult = AvailableStatus.UNCHECKABLE;
                try {
                    websiteCheckResult = requestFileInformationWebsite(link, isDownload);
                    if (websiteCheckResult == AvailableStatus.FALSE) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                    if (account == null) {
                        link.removeProperty(PROPERTY_ACCOUNT_REQUIRED);
                    }
                } catch (final AccountRequiredException aq) {
                    if (account != null) {
                        /*
                         * We're already logged in -> Item must be offline (or can only be accessed via another account with has the
                         * required permissions).
                         */
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    } else {
                        throw aq;
                    }
                }
            }
            this.setFilename(link);
            dllink = link.getStringProperty(PROPERTY_DIRECTURL);
            if (dllink != null && dllink.startsWith("http") && findAndCheckDownloadurl) {
                if (!this.checkDirecturlAndSetFilesize(link, dllink)) {
                    /* E.g. final downloadurl doesn't lead to video-file. */
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Broken video?");
                }
            }
            return AvailableStatus.TRUE;
        }
    }

    private String getUploaderURL(final DownloadLink link) {
        if (link.getPluginPatternMatcher().matches(TYPE_VIDEO_WITH_UPLOADER_NAME)) {
            return new Regex(link.getPluginPatternMatcher(), TYPE_VIDEO_WITH_UPLOADER_NAME).getMatch(0);
        } else {
            return link.getStringProperty(PROPERTY_UPLOADER_URL);
        }
    }

    private void setFilename(final DownloadLink link) {
        /* Some filename corrections */
        String filename = "";
        final String title = link.getStringProperty(PROPERTY_TITLE);
        final String dateFormatted = link.getStringProperty(PROPERTY_DATE_FORMATTED);
        final String uploader = link.getStringProperty(PROPERTY_UPLOADER);
        final String uploaderURL = getUploaderURL(link);
        if (dateFormatted != null) {
            filename += dateFormatted + "_";
        }
        final String uploaderNameForFilename = !StringUtils.isEmpty(uploader) ? uploader : uploaderURL;
        if (!StringUtils.isEmpty(uploaderNameForFilename)) {
            filename += uploaderNameForFilename + "_";
        }
        if (!StringUtils.isEmpty(title)) {
            filename += title.replaceAll("\\s*\\| Facebook\\s*$", "");
            if (!filename.contains(this.getFID(link))) {
                filename = filename + "_" + this.getFID(link);
            }
        } else {
            /* No title given at all -> use fuid only */
            filename += this.getFID(link);
        }
        filename += ".mp4";
        link.setFinalFileName(filename);
    }

    /**
     * Check video via https://m.facebook.com/watch/?v=<fid> </br>
     * In some cases, this may also work for videos for which an account is required otherwise. </br>
     * Not all videos can be accessed via this way!
     */
    @Deprecated
    private AvailableStatus requestFileInformationMobile(final DownloadLink link, final boolean isDownload) throws PluginException, IOException {
        /* 2021-01-11: Always try mobile website first!! Website crawler will fail for some videos otherwise! */
        br.setAllowedResponseCodes(new int[] { 500 });
        br.getPage("https://m.facebook.com/watch/?v=" + this.getFID(link));
        /* 2020-06-12: Website does never return appropriate 404 code so we have to check for strings in html :/ */
        if (br.getHttpConnection().getResponseCode() == 404 || br.getHttpConnection().getResponseCode() == 500) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("<title>\\s*Content not found\\s*</title>")) {
            /*
             * This can happen for content for which an account is required but there is no way for us to determine the "real" status so
             * let's assume that most of all users are trying to download public content and assume that this content is offline.
             */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        this.checkErrors();
        String fallback_downloadurl = null;
        if (!this.br.getURL().contains(this.getFID(link))) {
            /* Redirect to somewhere else -> Either offline or not playable via mobile website */
            return AvailableStatus.UNCHECKABLE;
        }
        /* Use whatever is in this variable as a fallback downloadurl if we fail to find one via embedded video call. */
        /* Get standardized json object "VideoObject" */
        String json = null;
        Map<String, Object> entries = null;
        String title = null;
        String uploader = null;
        String dateFormatted = null;
        try {
            entries = (Map<String, Object>) findSchemaJsonVideoObject();
            title = (String) entries.get("name");
            final String uploadDate = (String) entries.get("uploadDate");
            uploader = (String) JavaScriptEngineFactory.walkJson(entries, "author/name");
            if (!StringUtils.isEmpty(uploadDate)) {
                dateFormatted = new Regex(uploadDate, "(\\d{4}-\\d{2}-\\d{2})").getMatch(0);
            }
            /*
             * 2020-06-12: We avoid using this as final downloadurl [use only as fallback] as it is lower quality than via the "embed" way.
             * Also the given filesize is usually much higher than any stream we get --> That might be the original filesize of the uploaded
             * content ...
             */
            fallback_downloadurl = (String) entries.get("contentUrl");
            // final String contentSize = (String) entries.get("contentSize");
            // if (contentSize != null) {
            // link.setDownloadSize(SizeFormatter.getSize(contentSize));
            // }
        } catch (final Throwable e) {
            /* 2021-01-12: When user is loggedIN this is likely to happen! */
            logger.log(e);
            /*
             * 2020-08-20: Very very very very rare case: Redirect to: m.facebook.com/groups/12345678?view=permalink&id=12345678&_rdr
             */
            logger.info("json1 failed - trying to find alternative json");
            json = br.getRegex("data-store=\"([^\"]+videoID[^\"]+)").getMatch(0);
            if (json != null) {
                logger.info("Found json2");
                if (Encoding.isHtmlEntityCoded(json)) {
                    json = Encoding.htmlDecode(json);
                }
                /* This json doesn't contain anything useful for us other than the downloadurl */
                entries = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
                final String thisVideoID = (String) entries.get("videoID");
                if (thisVideoID == null) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else if (thisVideoID != this.getFID(link)) {
                    /* We got the json of a random other video --> Initial video must be offline */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                fallback_downloadurl = (String) entries.get("src");
                if (fallback_downloadurl != null) {
                    /* 2020-11-17: Fix sometimes double-escaped data */
                    fallback_downloadurl = fallback_downloadurl.replace("\\", "");
                }
                /* Try to get name of the uploader */
                if (entries.containsKey("videoURL")) {
                    String videoURL = (String) entries.get("videoURL");
                    if (!StringUtils.isEmpty(videoURL)) {
                        videoURL = PluginJSonUtils.unescape(videoURL);
                        final String uploaderURL = getUploaderNameFromVideoURL(videoURL);
                        if (uploaderURL != null) {
                            link.setProperty(PROPERTY_UPLOADER_URL, uploaderURL);
                        }
                    }
                }
            } else {
                logger.warning("Failed to find json2");
            }
            if (StringUtils.isEmpty(uploader)) {
                uploader = br.getRegex("tracking_source\\.video_home%3Aphoto_id\\." + this.getFID(link) + "%3Astory_location[^\"]+\">([^<]+)</a>").getMatch(0);
            }
            /* Hm still part of this strange edge-case ... */
            if (StringUtils.isEmpty(fallback_downloadurl)) {
                fallback_downloadurl = br.getRegex("/video_redirect/\\?src=(https?[^<>\"]+)\"").getMatch(0);
                if (fallback_downloadurl != null && Encoding.isHtmlEntityCoded(fallback_downloadurl)) {
                    fallback_downloadurl = Encoding.htmlDecode(fallback_downloadurl);
                }
            }
        }
        /*
         * Purposely do not check against empty string here! If we found an empty string inside json we will probably not find a better
         * result here!
         */
        if (title == null) {
            title = br.getRegex("property=\"og:title\" content=\"([^<>\"]+)\"").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("property=\"twitter:title\" content=\"([^<>\"]+)\"").getMatch(0);
        }
        if (title == null) {
            /* Final fallback - json is not always given and/or filename in json is not always given. */
            title = br.getRegex("<title>(.*?)</title>").getMatch(0);
        }
        if (dateFormatted == null) {
            /* Final fallback for uploadDate */
            final String dateStr = br.getRegex("photo_id\\." + this.getFID(link) + "%3Astory_location\\.\\d+%3Astory_attachment_style\\.video_inline%3Atds_flgs\\.\\d+%3A[^\"]+\"><abbr>([^<>\"]+)").getMatch(0);
            if (dateStr != null) {
                if (dateStr.matches("\\d{1,2}\\. [A-Za-z]+ \\d{4} um \\d{2}:\\d{2}")) {
                    final long timestamp = TimeFormatter.getMilliSeconds(dateStr, "dd'.' MMM yyyy 'um' HH:mm", Locale.GERMAN);
                    final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
                    dateFormatted = formatter.format(new Date(timestamp));
                } else if (dateStr.matches("[A-Za-z]+ \\d{1,2} at \\d{1,2}:\\d{1,2} (PM|AM)")) {
                    /* 2021-01-18: Bad workaround */
                    final long timestamp = TimeFormatter.getMilliSeconds("2021 " + dateStr, "yyyy MMM dd 'at' hh:mm aa", Locale.US);
                    final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
                    dateFormatted = formatter.format(new Date(timestamp));
                } else {
                    /* TODO: Add support for other dateformats */
                    logger.warning("Unsupported dateformat: " + dateStr);
                    dateFormatted = dateStr;
                }
            }
        }
        if (!StringUtils.isEmpty(title)) {
            link.setProperty(PROPERTY_TITLE, title);
        }
        if (!StringUtils.isEmpty(uploader)) {
            link.setProperty(PROPERTY_UPLOADER, uploader);
        }
        if (dateFormatted != null) {
            link.setProperty(PROPERTY_DATE_FORMATTED, dateFormatted);
        }
        if (fallback_downloadurl != null) {
            link.setProperty(PROPERTY_DIRECTURL, fallback_downloadurl);
        }
        return AvailableStatus.TRUE;
    }

    /**
     * 2021-03-18: "plugin embed" method. </br>
     * Not all videos are embeddable via this method thus make sure to have a fallback and do NOT trust offline status if this fails with
     * exception! </br>
     * This sometimes enables us to: </br>
     * 1. Check content that is otherwise only available when logedIN. </br>
     * 2. Get higher video quality. </br>
     * 3. Do superfast linkcheck as it's much faster than the FB website.
     */
    @Deprecated
    private AvailableStatus requestFileInformationPluginEmbed(final DownloadLink link, final boolean isDownload) throws Exception {
        br.getPage("https://www.facebook.com/v2.10/plugins/video.php?app_id=&channel=https%3A%2F%2Fstaticxx.facebook.com%2Fx%2Fconnect%2Fxd_arbiter%2F%3Fversion%3D46&container_width=678&href=https%3A%2F%2Fwww.facebook.com%2Fvideo.php%3Fv%3D" + this.getFID(link) + "&locale=de_DE&sdk=joey&width=500");
        final Object jsonO = websiteFindAndParseJson();
        final Object videoO = this.websiteFindVideoMap1(jsonO, this.getFID(link));
        /* TODO: Improve offline detection */
        if (videoO == null) {
            /**
             * 2021-03-18: We assume that if no video-json object is available, the video is either offline or not embeddable. </br>
             * They do return an errormessage for non-embeddable videos but it's language-dependant and thus not easy to parse so we don't
             * try to catch it here!
             */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* And again it's a pain to parse all the information we want... */
        String title = br.getRegex(this.getFID(link) + "\" target=\"_blank\"[^>]*>([^<>\"]+)</a>").getMatch(0);
        if (title != null) {
            link.setProperty(PROPERTY_TITLE, Encoding.htmlDecode(title).trim());
        }
        websitehandleVideoJson(link, jsonO);
        return AvailableStatus.TRUE;
    }

    private Object findSchemaJsonVideoObject() throws Exception {
        final String[] jsons = br.getRegex("<script[^>]*?type=\"application/ld\\+json\"[^>]*>(.*?)</script>").getColumn(0);
        for (final String json : jsons) {
            if (!json.contains("VideoObject")) {
                continue;
            } else {
                return JavaScriptEngineFactory.jsonToJavaObject(json);
            }
        }
        return null;
    }

    private Object websiteFindAndParseJson() {
        final String json = br.getRegex(org.appwork.utils.Regex.escape("<script>requireLazy([\"TimeSliceImpl\",\"ServerJS\"],function(TimeSlice,ServerJS){var s=(new ServerJS());s.handle(") + "(\\{.*?\\})\\);requireLazy\\(").getMatch(0);
        return JSonStorage.restoreFromString(json, TypeRef.OBJECT);
    }

    /**
     * Parses specific json object from website and sets all useful information as properties on given DownloadLink.
     *
     * @throws PluginException
     */
    private void websitehandleVideoJson(final DownloadLink link, final Object jsonO) throws PluginException {
        final Object videoO = this.websiteFindVideoMap1(jsonO, this.getFID(link));
        final Object htmlO = pluginEmbedFindHTMLInJson(jsonO, this.getFID(link));
        if (htmlO != null) {
            String specialHTML = (String) htmlO;
            specialHTML = PluginJSonUtils.unescape(specialHTML);
            final String uploader = new Regex(specialHTML, "alt=\"\" aria-label=\"([^\"]+)\"").getMatch(0);
            if (uploader != null) {
                link.setProperty(PROPERTY_UPLOADER, Encoding.htmlDecode(uploader).trim());
            }
        }
        Map<String, Object> entries = (Map<String, Object>) videoO;
        entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "videoData/{0}");
        final boolean isLivestream = ((Boolean) entries.get("is_live_stream")).booleanValue();
        if (isLivestream) {
            logger.info("Livestreams are not supported");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String[] sourcesInOrder;
        if (PluginJsonConfig.get(this.getConfigInterface()).isPreferHD()) {
            sourcesInOrder = new String[] { "hd_src_no_ratelimit", "hd_src", "sd_src_no_ratelimit", "sd_src" };
        } else {
            /* Other order if user doesn't prefer highest quality. */
            sourcesInOrder = new String[] { "sd_src_no_ratelimit", "sd_src", "hd_src_no_ratelimit", "hd_src" };
        }
        for (final String videosrc : sourcesInOrder) {
            final String dllink = (String) entries.get(videosrc);
            if (!StringUtils.isEmpty(dllink)) {
                logger.info("Found directurl using videosource: " + videosrc);
                link.setProperty(PROPERTY_DIRECTURL, dllink);
                break;
            }
        }
        /* Try to get name of the uploader */
        if (entries.containsKey("video_url")) {
            String videourl = (String) entries.get("video_url");
            if (!StringUtils.isEmpty(videourl)) {
                videourl = PluginJSonUtils.unescape(videourl);
                final String uploaderURL = getUploaderNameFromVideoURL(videourl);
                if (uploaderURL != null) {
                    link.setProperty(PROPERTY_UPLOADER_URL, uploaderURL);
                }
            }
        }
    }

    /**
     * Normal linkcheck via website. </br>
     * Only suited for URLs matching TYPE_VIDEO_WATCH! <br>
     * For other URL-types, website can be quite different depending on the video the user wants to download - it can redirect to random
     * other URLs!
     *
     * @throws PluginException
     */
    private AvailableStatus requestFileInformationWebsite(final DownloadLink link, final boolean isDownload) throws IOException, PluginException {
        br.getPage(link.getPluginPatternMatcher());
        this.checkErrors();
        if (link.getPluginPatternMatcher().matches(TYPE_VIDEO_WATCH) && !br.getURL().contains(this.getFID(link))) {
            /* E.g. https://www.facebook.com/watch/?v=2739449049644930 */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String dllink = null;
        Object jsonO1 = null;
        Object jsonO2 = null;
        Object errorO = null;
        final Form loginform = br.getFormbyProperty("id", "login_form");
        /* Different sources to parse their json. */
        final String[] jsonRegExes = new String[4];
        jsonRegExes[0] = "\"adp_CometVideoHomeInjectedLiveVideoQueryRelayPreloader_[a-f0-9]+\",(\\{\"__bbox\".*?)" + Regex.escape("]]]});});});");
        /* 2021-03-19: E.g. when user is loggedIN */
        jsonRegExes[1] = org.appwork.utils.Regex.escape("(new ServerJS()).handleWithCustomApplyEach(ScheduledApplyEach,") + "(\\{.*?\\})" + Regex.escape(");});});</script>");
        /* Same as previous RegEx but lazier. */
        jsonRegExes[2] = org.appwork.utils.Regex.escape("(new ServerJS()).handleWithCustomApplyEach(ScheduledApplyEach,") + "(\\{.*?\\})" + Regex.escape(");");
        jsonRegExes[3] = org.appwork.utils.Regex.escape("{bigPipe.onPageletArrive(") + "(\\{.*?\\})" + Regex.escape(");");
        final String fid = this.getFID(link);
        for (final String jsonRegEx : jsonRegExes) {
            final String[] jsons = br.getRegex(jsonRegEx).getColumn(0);
            for (final String json : jsons) {
                try {
                    final Object jsonO = JavaScriptEngineFactory.jsonToJavaMap(json);
                    /* 2021-03-23: Use JavaScriptEngineFactory as they can also have json without quotes around the keys. */
                    // final Object jsonO = JSonStorage.restoreFromString(json, TypeRef.OBJECT);
                    jsonO1 = this.websiteFindVideoMap1(jsonO, fid);
                    if (jsonO1 != null) {
                        break;
                    }
                    jsonO2 = this.websiteFindVideoMap2(jsonO, fid);
                    if (jsonO2 != null) {
                        break;
                    }
                    if (errorO == null) {
                        errorO = this.websiteFindErrorMap(jsonO, fid);
                    }
                } catch (final Throwable ignore) {
                }
            }
        }
        if (jsonO1 != null) {
            if (DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                System.out.print(JSonStorage.serializeToJson(jsonO1));
            }
            this.websitehandleVideoJson(link, jsonO1);
            // if (!link.hasProperty(PROPERTY_UPLOADER) && link.hasProperty(PROPERTY_UPLOADER_URL)) {
            // final String uploaderURL = link.getStringProperty(PROPERTY_UPLOADER_URL);
            // final String uploader = br.getRegex("<a href=\"/watch/" + org.appwork.utils.Regex.escape(uploaderURL) +
            // "/?\"[^>]*id=\"[^\"]+\"[^>]*>([^<>\"]+)</a>").getMatch(0);
            // if (uploader != null) {
            // link.setProperty(PROPERTY_UPLOADER, uploader);
            // }
            // }
            // if (!link.hasProperty(PROPERTY_TITLE)) {
            // final String title = br.getRegex("<meta property=\"og:title\" content=\"([^\"]+)\" />").getMatch(0);
            // if (title != null) {
            // link.setProperty(PROPERTY_TITLE, title);
            // }
            // }
            /**
             * Try to find extra data for nicer filenames. </br>
             * Do not trust this source 100% so only set properties which haven't been set before!
             */
            try {
                final Map<String, Object> entries = (Map<String, Object>) findSchemaJsonVideoObject();
                final String title = (String) entries.get("name");
                final String uploadDate = (String) entries.get("uploadDate");
                final String uploader = (String) JavaScriptEngineFactory.walkJson(entries, "author/name");
                if (!StringUtils.isEmpty(uploadDate)) {
                    final String dateFormatted = new Regex(uploadDate, "(\\d{4}-\\d{2}-\\d{2})").getMatch(0);
                    if (dateFormatted != null && !link.hasProperty(PROPERTY_DATE_FORMATTED)) {
                        link.setProperty(PROPERTY_DATE_FORMATTED, dateFormatted);
                    }
                }
                if (!StringUtils.isEmpty(title) && !link.hasProperty(PROPERTY_TITLE)) {
                    link.setProperty(PROPERTY_TITLE, title);
                }
                if (!StringUtils.isEmpty(uploader) && !link.hasProperty(PROPERTY_UPLOADER)) {
                    link.setProperty(PROPERTY_UPLOADER, uploader);
                }
                /* 2021-03-23: Don't use this. Normal website video json provides higher quality! */
                // fallback_downloadurl = (String) entries.get("contentUrl");
            } catch (final Throwable ignore) {
            }
            return AvailableStatus.TRUE;
        } else if (jsonO2 != null) {
            logger.info("Found jsonO2");
            Map<String, Object> entries = (Map<String, Object>) jsonO2;
            final boolean isLivestream = ((Boolean) entries.get("is_live_streaming")).booleanValue();
            if (isLivestream) {
                logger.info("Livestreams are not supported");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (entries.containsKey("playable_duration_in_ms")) {
                /* Set this as a possible packagizer property. */
                link.setProperty(PROPERTY_RUNTIME_MILLISECONDS, ((Number) entries.get("playable_duration_in_ms")).longValue());
            }
            final String title = (String) entries.get("name");
            final String uploader = (String) JavaScriptEngineFactory.walkJson(entries, "owner/name");
            if (entries.containsKey("publish_time")) {
                final long publish_time = ((Number) entries.get("publish_time")).longValue();
                final Date date = new Date(publish_time * 1000);
                final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
                link.setProperty(PROPERTY_DATE_FORMATTED, formatter.format(date));
            }
            try {
                /* Find- and set description if possible */
                final String description = (String) JavaScriptEngineFactory.walkJson(entries, "savable_description/text");
                if (!StringUtils.isEmpty(description) && StringUtils.isEmpty(link.getComment())) {
                    link.setComment(description);
                }
            } catch (final Throwable ignore) {
            }
            try {
                final String permalink_url = (String) entries.get("permalink_url");
                final String uploaderURL = this.getUploaderNameFromVideoURL(permalink_url);
                if (uploaderURL != null) {
                    link.setProperty(PROPERTY_UPLOADER_URL, uploaderURL);
                }
            } catch (final Throwable ignore) {
            }
            final String urlLow = (String) entries.get("playable_url");
            final String urlHigh = (String) entries.get("playable_url_quality_hd");
            if (PluginJsonConfig.get(this.getConfigInterface()).isPreferHD() && !StringUtils.isEmpty(urlHigh)) {
                logger.info("User quality: playable_url_quality_hd");
                dllink = urlHigh;
            } else {
                logger.info("User quality: playable_url");
                dllink = urlLow;
            }
            if (!StringUtils.isEmpty(title)) {
                link.setProperty(PROPERTY_TITLE, title);
            }
            if (!StringUtils.isEmpty(uploader)) {
                link.setProperty(PROPERTY_UPLOADER, uploader);
            }
            if (!StringUtils.isEmpty(dllink)) {
                link.setProperty(PROPERTY_DIRECTURL, dllink);
            }
            return AvailableStatus.TRUE;
        } else if (errorO != null) {
            /*
             * Video offline or we don't have access to it -> Website doesn't return a clear errormessage either -> Let's handle it as
             * offline!
             */
            return AvailableStatus.FALSE;
        } else if (loginform != null) {
            throw new AccountRequiredException();
        } else {
            logger.warning("Failed to find jsonO2");
            return AvailableStatus.UNCHECKABLE;
        }
    }

    private Object websiteFindErrorMap(final Object o, final String videoid) {
        if (o instanceof Map) {
            final Map<String, Object> entrymap = (Map<String, Object>) o;
            for (final Map.Entry<String, Object> cookieEntry : entrymap.entrySet()) {
                final String key = cookieEntry.getKey();
                final Object value = cookieEntry.getValue();
                if (key.equals("rootView") && value instanceof Map && entrymap.containsKey("tracePolicy")) {
                    final String tracePolicy = (String) entrymap.get("tracePolicy");
                    final String videoidTmp = (String) JavaScriptEngineFactory.walkJson(entrymap, "params/video_id");
                    if (StringUtils.equals(tracePolicy, "comet.error") && StringUtils.equals(videoidTmp, videoid)) {
                        return o;
                    }
                } else if (value instanceof List || value instanceof Map) {
                    final Object pico = websiteFindErrorMap(value, videoid);
                    if (pico != null) {
                        return pico;
                    }
                }
            }
            return null;
        } else if (o instanceof List) {
            final List<Object> array = (List) o;
            for (final Object arrayo : array) {
                if (arrayo instanceof List || arrayo instanceof Map) {
                    final Object pico = websiteFindErrorMap(arrayo, videoid);
                    if (pico != null) {
                        return pico;
                    }
                }
            }
            return null;
        } else {
            return null;
        }
    }

    private Object websiteFindVideoMap1(final Object o, final String videoid) {
        if (o instanceof Map) {
            final Map<String, Object> entrymap = (Map<String, Object>) o;
            for (final Map.Entry<String, Object> cookieEntry : entrymap.entrySet()) {
                final String key = cookieEntry.getKey();
                final Object value = cookieEntry.getValue();
                if (key.equals("video_id") && value instanceof String && entrymap.containsKey("videoData")) {
                    final String entry_id = (String) value;
                    if (entry_id.equals(videoid)) {
                        return o;
                    } else {
                        continue;
                    }
                } else if (value instanceof List || value instanceof Map) {
                    final Object pico = websiteFindVideoMap1(value, videoid);
                    if (pico != null) {
                        return pico;
                    }
                }
            }
            return null;
        } else if (o instanceof List) {
            final List<Object> array = (List) o;
            for (final Object arrayo : array) {
                if (arrayo instanceof List || arrayo instanceof Map) {
                    final Object pico = websiteFindVideoMap1(arrayo, videoid);
                    if (pico != null) {
                        return pico;
                    }
                }
            }
            return null;
        } else {
            return null;
        }
    }

    private Object websiteFindVideoMap2(final Object o, final String videoid) {
        if (o instanceof Map) {
            final Map<String, Object> entrymap = (Map<String, Object>) o;
            for (final Map.Entry<String, Object> cookieEntry : entrymap.entrySet()) {
                final String key = cookieEntry.getKey();
                final Object value = cookieEntry.getValue();
                if (key.equals("id") && value instanceof String && entrymap.containsKey("is_live_streaming")) {
                    final String entry_id = (String) value;
                    if (entry_id.equals(videoid)) {
                        return o;
                    } else {
                        continue;
                    }
                } else if (value instanceof List || value instanceof Map) {
                    final Object pico = websiteFindVideoMap2(value, videoid);
                    if (pico != null) {
                        return pico;
                    }
                }
            }
            return null;
        } else if (o instanceof List) {
            final List<Object> array = (List) o;
            for (final Object arrayo : array) {
                if (arrayo instanceof List || arrayo instanceof Map) {
                    final Object pico = websiteFindVideoMap2(arrayo, videoid);
                    if (pico != null) {
                        return pico;
                    }
                }
            }
            return null;
        } else {
            return null;
        }
    }

    /**
     * Linkcheck via embed URL. </br>
     * Not all videos are embeddable!
     */
    public AvailableStatus requestFileInformationEmbed(final DownloadLink link, final boolean isDownload) throws Exception {
        br.getPage("https://www." + this.getHost() + "/video/embed?video_id=" + this.getFID(link));
        /*
         * 2020-06-05: <div class="pam uiBoxRed"><div class="fsl fwb fcb">Video nicht verfügbar</div>Dieses Video wurde entweder entfernt
         * oder ist aufgrund der ‎Privatsphäre-Einstellungen nicht sichtbar.</div>
         */
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("class=\"pam uiBoxRed\"")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Object jsonO = websiteFindAndParseJson();
        websitehandleVideoJson(link, jsonO);
        return AvailableStatus.TRUE;
    }

    private boolean checkDirecturlAndSetFilesize(final DownloadLink link, final String directurl) throws IOException, PluginException {
        URLConnectionAdapter con = null;
        try {
            con = br.openHeadConnection(directurl);
            if (this.looksLikeDownloadableContent(con)) {
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
                return true;
            }
        } finally {
            try {
                con.disconnect();
            } catch (final Throwable e) {
            }
        }
        return false;
    }

    private Object pluginEmbedFindHTMLInJson(final Object o, final String videoid) {
        if (o instanceof Map) {
            final Map<String, Object> entrymap = (Map<String, Object>) o;
            for (final Map.Entry<String, Object> cookieEntry : entrymap.entrySet()) {
                final String key = cookieEntry.getKey();
                final Object value = cookieEntry.getValue();
                if (key.equals("__html") && value instanceof String && ((String) value).contains(videoid)) {
                    return value;
                } else if (value instanceof List || value instanceof Map) {
                    final Object target = pluginEmbedFindHTMLInJson(value, videoid);
                    if (target != null) {
                        return target;
                    }
                }
            }
            return null;
        } else if (o instanceof List) {
            final List<Object> array = (List) o;
            for (final Object arrayo : array) {
                if (arrayo instanceof List || arrayo instanceof Map) {
                    final Object pico = pluginEmbedFindHTMLInJson(arrayo, videoid);
                    if (pico != null) {
                        return pico;
                    }
                }
            }
            return null;
        } else {
            return null;
        }
    }

    public AvailableStatus requestFileInformationPhoto(final DownloadLink link, final boolean isDownload) throws Exception {
        String dllink = link.getStringProperty(PROPERTY_DIRECTURL, null);
        if (!link.isNameSet()) {
            link.setName(this.getFID(link) + ".jpg");
        }
        this.prepBR(this.br);
        final Account aa = AccountController.getInstance().getValidAccount(this.getHost());
        if (aa != null) {
            login(aa, this.br, false);
        }
        br.getPage(link.getPluginPatternMatcher());
        final String[] jsons = br.getRegex(org.appwork.utils.Regex.escape("\"ServerJS\",\"ScheduledApplyEach\"],function(JSScheduler,ServerJS,ScheduledApplyEach){JSScheduler.runWithPriority(3,function(){(new ServerJS()).handleWithCustomApplyEach(ScheduledApplyEach,") + "(\\{.*?\\})\\);</script>").getColumn(0);
        for (final String json : jsons) {
            final Object jsonO = JSonStorage.restoreFromString(json, TypeRef.OBJECT);
            final Map<String, Object> photoMap = (Map<String, Object>) findPhotoMap(jsonO, this.getFID(link));
            if (photoMap != null) {
                dllink = (String) JavaScriptEngineFactory.walkJson(photoMap, "image/uri");
                break;
            }
        }
        if (!StringUtils.isEmpty(dllink)) {
            maxChunks = 1;
            this.checkDirecturlAndSetFilesize(link, dllink);
            /* TODO: Improve filenames */
            link.setFinalFileName(this.getFID(link) + ".jpg");
            link.setProperty(PROPERTY_DIRECTURL, dllink);
        }
        return AvailableStatus.TRUE;
    }

    /** Recursive function to find photoMap inside json. */
    private Object findPhotoMap(final Object o, final String photoid) {
        if (o instanceof Map) {
            final Map<String, Object> entrymap = (Map<String, Object>) o;
            for (final Map.Entry<String, Object> cookieEntry : entrymap.entrySet()) {
                final String key = cookieEntry.getKey();
                final Object value = cookieEntry.getValue();
                if (key.equals("id") && value instanceof String) {
                    final String entry_id = (String) value;
                    if (entry_id.equals(photoid) && entrymap.containsKey("__isMedia") && entrymap.containsKey("image")) {
                        return o;
                    } else {
                        continue;
                    }
                } else if (value instanceof List || value instanceof Map) {
                    final Object pico = findPhotoMap(value, photoid);
                    if (pico != null) {
                        return pico;
                    }
                }
            }
            return null;
        } else if (o instanceof List) {
            final List<Object> array = (List) o;
            for (final Object arrayo : array) {
                if (arrayo instanceof List || arrayo instanceof Map) {
                    final Object pico = findPhotoMap(arrayo, photoid);
                    if (pico != null) {
                        return pico;
                    }
                }
            }
            return null;
        } else {
            return null;
        }
    }

    private String getUploaderNameFromVideoURL(final String videourl) {
        if (videourl == null) {
            return null;
        } else {
            return new Regex(videourl, "(?i)https?://[^/]+/([^/]+)/videos/.*").getMatch(0);
        }
    }

    private void checkErrors() throws AccountRequiredException {
        if (br.getURL().contains("/login.php") || br.getURL().contains("/login/?next=")) {
            /*
             * 2021-03-01: Login required: There are videos which are only available via account but additionally it seems like FB randomly
             * enforces the need of an account for other videos also e.g. by country/IP.
             */
            throw new AccountRequiredException();
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        login(account, this.br, true);
        final AccountInfo ai = new AccountInfo();
        ai.setStatus("Valid Facebook account is active");
        ai.setUnlimitedTraffic();
        return ai;
    }

    @Override
    public String getAGBLink() {
        return "http://www.facebook.com/terms.php";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        handleDownload(link, null);
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        handleDownload(link, account);
    }

    public void handleDownload(final DownloadLink link, final Account account) throws Exception {
        if (!attemptStoredDownloadurlDownload(link)) {
            requestFileInformation(link, account, true);
            final String dllink = link.getStringProperty(PROPERTY_DIRECTURL);
            if (dllink == null) {
                if (link.getBooleanProperty(PROPERTY_ACCOUNT_REQUIRED, false)) {
                    /*
                     * If this happens while an account is active this means that the user is either missing the rights to access that item
                     * or the item is offline.
                     */
                    throw new AccountRequiredException();
                } else {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, maxChunks);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Broken file?");
            }
        }
        dl.startDownload();
    }

    private boolean attemptStoredDownloadurlDownload(final DownloadLink link) throws Exception {
        final String url = link.getStringProperty(PROPERTY_DIRECTURL);
        if (url == null) {
            return false;
        }
        try {
            dl = new jd.plugins.BrowserAdapter().openDownload(br, this.getDownloadLink(), url, true, maxChunks);
            if (this.looksLikeDownloadableContent(dl.getConnection())) {
                return true;
            } else {
                throw new IOException();
            }
        } catch (final Throwable e) {
            logger.log(e);
            try {
                dl.getConnection().disconnect();
            } catch (Throwable ignore) {
            }
            return false;
        }
    }

    public void login(final Account account, final Browser br, final boolean force) throws Exception {
        synchronized (account) {
            try {
                prepBR(br);
                // Load cookies
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                /* 2020-10-9: Experimental login/test */
                final Cookies userCookies = Cookies.parseCookiesFromJsonString(account.getPass());
                final boolean enforceCookieLogin = true;
                if (cookies != null) {
                    br.setCookies(this.getHost(), cookies);
                    if (System.currentTimeMillis() - account.getCookiesTimeStamp("") <= trust_cookie_age && !force) {
                        /* We trust these cookies --> Do not check them */
                        logger.info("Trust login cookies");
                        return;
                    }
                    final boolean follow = br.isFollowingRedirects();
                    try {
                        br.setFollowRedirects(true);
                        br.getPage("https://" + this.getHost() + "/");
                    } finally {
                        br.setFollowRedirects(follow);
                    }
                    if (this.isLoggedinHTML()) {
                        /* Save cookies to save new valid cookie timestamp */
                        logger.info("Cookie login successful");
                        account.saveCookies(br.getCookies(this.getHost()), "");
                        return;
                    } else {
                        logger.info("Cookie login failed");
                        /* Get rid of old cookies / headers */
                        br.clearAll();
                        prepBR(br);
                    }
                }
                logger.info("Performing full login");
                if (userCookies != null) {
                    logger.info("Trying to login via user-cookies");
                    br.setCookies(userCookies);
                    final boolean follow = br.isFollowingRedirects();
                    try {
                        br.setFollowRedirects(true);
                        br.getPage("https://" + this.getHost() + "/");
                    } finally {
                        br.setFollowRedirects(follow);
                    }
                    if (this.isLoggedinHTML()) {
                        /* Save cookies to save new valid cookie timestamp */
                        logger.info("User-cookie login successful");
                        account.saveCookies(br.getCookies(this.getHost()), "");
                        /*
                         * Try to make sure that username in JD is unique because via cookie login, user can enter whatever he wants into
                         * username field! 2020-11-16: Username can be "" (empty) for some users [rare case].
                         */
                        final String username = PluginJSonUtils.getJson(br, "username");
                        if (!StringUtils.isEmpty(username)) {
                            logger.info("Found username in json: " + username);
                            account.setUser(username);
                        } else {
                            logger.info("Failed to find username in json (rarec case)");
                        }
                        return;
                    } else {
                        logger.info("User-Cookie login failed");
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "Cookie login failed", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                } else if (enforceCookieLogin) {
                    showCookieLoginInformation();
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "Cookie login required", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                br.setFollowRedirects(true);
                final boolean prefer_mobile_login = true;
                // better use the website login. else the error handling below might be broken.
                if (prefer_mobile_login) {
                    /* Mobile login = no crypto crap */
                    br.getPage("https://m.facebook.com/");
                    final Form loginForm = br.getForm(0);
                    if (loginForm == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    loginForm.remove(null);
                    loginForm.put("email", Encoding.urlEncode(account.getUser()));
                    loginForm.put("pass", Encoding.urlEncode(account.getPass()));
                    br.submitForm(loginForm);
                    br.getPage("https://www.facebook.com/");
                } else {
                    br.getPage("https://www.facebook.com/login.php");
                    final String lang = System.getProperty("user.language");
                    final Form loginForm = br.getForm(0);
                    if (loginForm == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    loginForm.remove("persistent");
                    loginForm.put("persistent", "1");
                    loginForm.remove(null);
                    loginForm.remove("login");
                    loginForm.remove("trynum");
                    loginForm.remove("profile_selector_ids");
                    loginForm.remove("legacy_return");
                    loginForm.remove("enable_profile_selector");
                    loginForm.remove("display");
                    String _js_datr = br.getRegex("\"_js_datr\"\\s*,\\s*\"([^\"]+)").getMatch(0);
                    br.setCookie("https://facebook.com", "_js_datr", _js_datr);
                    br.setCookie("https://facebook.com", "_js_reg_fb_ref", Encoding.urlEncode("https://www.facebook.com/login.php"));
                    br.setCookie("https://facebook.com", "_js_reg_fb_gate", Encoding.urlEncode("https://www.facebook.com/login.php"));
                    loginForm.put("email", Encoding.urlEncode(account.getUser()));
                    loginForm.put("pass", Encoding.urlEncode(account.getPass()));
                    br.submitForm(loginForm);
                }
                /**
                 * Facebook thinks we're an unknown device, now we prove we're not ;)
                 */
                if (br.containsHTML(">Your account is temporarily locked")) {
                    final String nh = br.getRegex("name=\"nh\" value=\"([a-z0-9]+)\"").getMatch(0);
                    final String dstc = br.getRegex("name=\"fb_dtsg\" value=\"([^<>\"]*?)\"").getMatch(0);
                    if (nh == null || dstc == null) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                    br.postPage(br.getURL(), "fb_dtsg=" + Encoding.urlEncode(dstc) + "&nh=" + nh + "&submit%5BContinue%5D=Continue");
                    final DownloadLink dummyLink = new DownloadLink(this, "Account", "facebook.com", "http://facebook.com", true);
                    String achal = br.getRegex("name=\"achal\" value=\"([a-z0-9]+)\"").getMatch(0);
                    final String captchaPersistData = br.getRegex("name=\"captcha_persist_data\" value=\"([^<>\"]*?)\"").getMatch(0);
                    if (captchaPersistData == null || achal == null) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                    // Normal captcha handling
                    for (int i = 1; i <= 3; i++) {
                        String captchaLink = br.getRegex("\"(https?://(www\\.)?facebook\\.com/captcha/tfbimage\\.php\\?captcha_challenge_code=[^<>\"]*?)\"").getMatch(0);
                        if (captchaLink == null) {
                            break;
                        }
                        captchaLink = Encoding.htmlDecode(captchaLink);
                        String code;
                        try {
                            code = getCaptchaCode(captchaLink, dummyLink);
                        } catch (final Exception e) {
                            continue;
                        }
                        br.postPage(br.getURL(), "fb_dtsg=" + Encoding.urlEncode(dstc) + "&nh=" + nh + "&geo=true&captcha_persist_data=" + Encoding.urlEncode(captchaPersistData) + "&captcha_response=" + Encoding.urlEncode(code) + "&achal=" + achal + "&submit%5BSubmit%5D=Submit");
                    }
                    // reCaptcha handling
                    for (int i = 1; i <= 3; i++) {
                        final String rcID = br.getRegex("\"recaptchaPublicKey\":\"([^<>\"]*?)\"").getMatch(0);
                        if (rcID == null) {
                            break;
                        }
                        final String extraChallengeParams = br.getRegex("name=\"extra_challenge_params\" value=\"([^<>\"]*?)\"").getMatch(0);
                        final String captchaSession = br.getRegex("name=\"captcha_session\" value=\"([^<>\"]*?)\"").getMatch(0);
                        if (extraChallengeParams == null || captchaSession == null) {
                            break;
                        }
                        final Recaptcha rc = new Recaptcha(br, this);
                        rc.setId(rcID);
                        rc.load();
                        final File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                        String c;
                        try {
                            c = getCaptchaCode("recaptcha", cf, dummyLink);
                        } catch (final Exception e) {
                            continue;
                        }
                        br.postPage(br.getURL(), "fb_dtsg=" + Encoding.urlEncode(dstc) + "&nh=" + nh + "&geo=true&captcha_persist_data=" + Encoding.urlEncode(captchaPersistData) + "&captcha_session=" + Encoding.urlEncode(captchaSession) + "&extra_challenge_params=" + Encoding.urlEncode(extraChallengeParams) + "&recaptcha_type=password&recaptcha_challenge_field=" + Encoding.urlEncode(rc.getChallenge()) + "&captcha_response=" + Encoding.urlEncode(c) + "&achal=1&submit%5BSubmit%5D=Submit");
                    }
                    for (int i = 1; i <= 3; i++) {
                        if (br.containsHTML(">To confirm your identity, please enter your birthday")) {
                            achal = br.getRegex("name=\"achal\" value=\"([a-z0-9]+)\"").getMatch(0);
                            if (achal == null) {
                                break;
                            }
                            String birthdayVerificationAnswer;
                            try {
                                birthdayVerificationAnswer = getUserInput("Enter your birthday (dd:MM:yyyy)", dummyLink);
                            } catch (final Exception e) {
                                continue;
                            }
                            final String[] bdSplit = birthdayVerificationAnswer.split(":");
                            if (bdSplit == null || bdSplit.length != 3) {
                                continue;
                            }
                            int bdDay = 0, bdMonth = 0, bdYear = 0;
                            try {
                                bdDay = Integer.parseInt(bdSplit[0]);
                                bdMonth = Integer.parseInt(bdSplit[1]);
                                bdYear = Integer.parseInt(bdSplit[2]);
                            } catch (final Exception e) {
                                continue;
                            }
                            br.postPage(br.getURL(), "fb_dtsg=" + Encoding.urlEncode(dstc) + "&nh=" + nh + "&geo=true&birthday_captcha_month=" + bdMonth + "&birthday_captcha_day=" + bdDay + "&birthday_captcha_year=" + bdYear + "&captcha_persist_data=" + Encoding.urlEncode(captchaPersistData) + "&achal=" + achal + "&submit%5BSubmit%5D=Submit");
                        } else {
                            break;
                        }
                    }
                    if (br.containsHTML("/captcha/friend_name_image\\.php\\?")) {
                        // unsupported captcha challange.
                        logger.warning("Unsupported captcha challenge.");
                    }
                } else if (br.containsHTML("/checkpoint/")) {
                    br.getPage("https://www.facebook.com/checkpoint/");
                    final String postFormID = br.getRegex("name=\"post_form_id\" value=\"(.*?)\"").getMatch(0);
                    final String nh = br.getRegex("name=\"nh\" value=\"(.*?)\"").getMatch(0);
                    if (nh == null) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                    br.postPage("https://www.facebook.com/checkpoint/", "post_form_id=" + postFormID + "&lsd=GT_Up&submit%5BContinue%5D=Weiter&nh=" + nh);
                    br.postPage("https://www.facebook.com/checkpoint/", "post_form_id=" + postFormID + "&lsd=GT_Up&submit%5BThis+is+Okay%5D=Das+ist+OK&nh=" + nh);
                    br.postPage("https://www.facebook.com/checkpoint/", "post_form_id=" + postFormID + "&lsd=GT_Up&machine_name=&submit%5BDon%27t+Save%5D=Nicht+speichern&nh=" + nh);
                    br.postPage("https://www.facebook.com/checkpoint/", "post_form_id=" + postFormID + "&lsd=GT_Up&machine_name=&submit%5BDon%27t+Save%5D=Nicht+speichern&nh=" + nh);
                } else if (br.getURL().contains("/login/save-device")) {
                    /* 2020-10-29: Challenge kinda like "Trust this device" */
                    final Form continueForm = br.getFormbyActionRegex(".*/login/device-based/.*");
                    if (continueForm == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    br.submitForm(continueForm);
                    br.getPage("https://" + this.getHost() + "/");
                    br.followRedirect();
                }
                if (!isLoggedinHTML()) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                /* Save cookies */
                account.saveCookies(br.getCookies(this.getHost()), "");
            } catch (PluginException e) {
                if (e.getLinkStatus() == PluginException.VALUE_ID_PREMIUM_DISABLE) {
                    account.removeProperty("");
                }
                throw e;
            }
        }
    }

    private Thread showCookieLoginInformation() {
        final Thread thread = new Thread() {
            public void run() {
                try {
                    final String help_article_url = "https://support.jdownloader.org/Knowledgebase/Article/View/account-cookie-login-instructions";
                    String message = "";
                    final String title;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        title = "Facebook - Login";
                        message += "Hallo liebe(r) Facebook NutzerIn\r\n";
                        message += "Um deinen Facebook Account in JDownloader verwenden zu können, musst du folgende Schritte beachten:\r\n";
                        message += "Folge der Anleitung im Hilfe-Artikel:\r\n";
                        message += help_article_url;
                    } else {
                        title = "Facebook - Login";
                        message += "Hello dear Facebook user\r\n";
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
                    getLogger().log(e);
                }
            };
        };
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private boolean isLoggedinHTML() {
        final boolean brContainsSecondaryLoggedinHint = br.containsHTML("settings_dropdown_profile_picture");
        final String logout_hash = PluginJSonUtils.getJson(br, "logout_hash");
        logger.info("logout_hash = " + logout_hash);
        logger.info("brContainsSecondaryLoggedinHint = " + brContainsSecondaryLoggedinHint);
        return !StringUtils.isEmpty(logout_hash) && brContainsSecondaryLoggedinHint;
    }

    public boolean allowHandle(final DownloadLink link, final PluginForHost plugin) {
        /* No not allow multihost plugins to handle Facebook URLs! */
        return link.getHost().equalsIgnoreCase(plugin.getHost());
    }

    @Override
    public Class<? extends FacebookConfig> getConfigInterface() {
        return FacebookConfig.class;
    }

    private static String getUser(final Browser br) {
        return jd.plugins.decrypter.FaceBookComGallery.getUser(br);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
        if (DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
            link.removeProperty(PROPERTY_DIRECTURL);
            link.removeProperty(PROPERTY_IS_CHECKABLE_VIA_PLUGIN_EMBED);
            link.removeProperty(PROPERTY_ACCOUNT_REQUIRED);
            link.removeProperty(PROPERTY_TITLE);
            link.removeProperty(PROPERTY_UPLOADER);
            link.removeProperty(PROPERTY_UPLOADER_URL);
        }
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        /* Only login captcha sometimes */
        return false;
    }
}