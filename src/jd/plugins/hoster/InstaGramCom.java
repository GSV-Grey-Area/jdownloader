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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.uio.ConfirmDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.DebugMode;
import org.appwork.utils.IO;
import org.appwork.utils.StringUtils;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.plugins.components.config.InstagramConfig;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.RequestHeader;
import jd.http.URLConnectionAdapter;
import jd.http.requests.PostRequest;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.parser.html.Form.MethodType;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.CryptedLink;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.decrypter.InstaGramComDecrypter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 4, names = { "instagram.com" }, urls = { "https?://(?:www\\.)?instagram.com/p/[A-Za-z0-9_-]+/" })
public class InstaGramCom extends PluginForHost {
    @SuppressWarnings("deprecation")
    public InstaGramCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(MAINPAGE + "/accounts/login/");
    }

    @Override
    public void init() {
        InstaGramComDecrypter.setRequestIntervalLimitGlobal();
    }

    public static Browser prepBRAltAPI(final Browser br) {
        /* https://github.com/qsniyg/maxurl/blob/master/userscript.user.js */
        br.getHeaders().put("User-Agent", "Instagram 146.0.0.27.125 Android (23/6.0.1; 640dpi; 1440x2560; samsung; SM-G930F; herolte; samsungexynos8890; en_US)");
        br.setAllowedResponseCodes(new int[] { 429 });
        return br;
    }

    private String dllink = null;

    @Override
    public String getAGBLink() {
        return MAINPAGE + "/about/legal/terms/";
    }

    /**
     * https://instagram.api-docs.io/1.0 </br>
     */
    public static final String  ALT_API_BASE                             = "https://i.instagram.com/api/v1";
    /* Connection stuff */
    private final boolean       RESUME                                   = true;
    /* Chunkload makes no sense for pictures/small files */
    private final int           MAXCHUNKS_pictures                       = 1;
    /* 2020-01-21: Multi chunks are possible but it's better not to do this to avoid getting blocked! */
    private final int           MAXCHUNKS_videos                         = 1;
    private final int           MAXDOWNLOADS                             = -1;
    private static final String MAINPAGE                                 = "https://www.instagram.com";
    /* DownloadLink/Packagizer properties */
    public static final String  PROPERTY_has_tried_to_crawl_original_url = "has_tried_to_crawl_original_url";
    public static final String  PROPERTY_is_part_of_story                = "is_part_of_story";
    public static final String  PROPERTY_DIRECTURL                       = "directurl";
    public static final String  PROPERTY_private_url                     = "private_url";
    public static final String  PROPERTY_internal_media_id               = "internal_media_id";
    public static final String  PROPERTY_orderid                         = "orderid";
    public static final String  PROPERTY_orderid_raw                     = "orderid_raw";
    public static final String  PROPERTY_orderid_max_raw                 = "orderid_max_raw";                // number of items inside
    // post/story
    public static final String  PROPERTY_shortcode                       = "shortcode";
    public static final String  PROPERTY_description                     = "description";
    public static final String  PROPERTY_uploader                        = "uploader";
    public static final String  PROPERTY_type                            = "type";
    public static final String  PROPERTY_date                            = "date";
    public static final String  PROPERTY_hashtag                         = "hashtag";
    @Deprecated
    public static final String  PROPERTY_filename_from_crawler           = "decypter_filename";              // used until crawler rev 45795
    public static final String  PROPERTY_main_content_id                 = "main_content_id";                // e.g.
                                                                                                             // instagram.com/p/<main_content_id>/
    public static final String  PROPERTY_forced_packagename              = "forced_packagename";
    public static final String  PROPERTY_is_private                      = "is_private";

    public static void setRequestLimit() {
        Browser.setRequestIntervalLimitGlobal("instagram.com", true, 8000);
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String internalMediaID = this.getInternalMediaID(link);
        if (internalMediaID != null) {
            final String mediaIDWithoutUsername;
            if (internalMediaID.matches("\\d+_\\d+")) {
                mediaIDWithoutUsername = internalMediaID.split("_")[0];
            } else {
                mediaIDWithoutUsername = internalMediaID;
            }
            return this.getHost() + "://" + mediaIDWithoutUsername;
        } else if (link.getName() != null && link.getName().endsWith(".txt")) {
            return this.getHost() + "://" + link.getName();
        } else {
            return super.getLinkID(link);
        }
    }

    /** Can be only numbers or : <numbers>_<userID> */
    private String getInternalMediaID(final DownloadLink link) {
        final String idFromLegacyProperty = link.getStringProperty("postid"); // backward compatibility
        if (idFromLegacyProperty != null) {
            return idFromLegacyProperty;
        } else {
            return link.getStringProperty(PROPERTY_internal_media_id);
        }
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        return requestFileInformation(link, account, false);
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        this.correctDownloadLink(link);
        dllink = null;
        this.setBrowserExclusive();
        if (isText(link)) {
            /* Text we want to save to a file is stored as property -> Such items are always cannot be "offline"! */
            final String filename = InstaGramComDecrypter.getFilename(link);
            if (filename != null) {
                link.setFinalFileName(filename);
            }
            return AvailableStatus.TRUE;
        } else {
            prepBRWebsite(this.br);
            boolean isLoggedIN = false;
            if (PluginJsonConfig.get(InstagramConfig.class).isAttemptToDownloadOriginalQuality() && !hasTriedToCrawlOriginalQuality(link) && account != null) {
                login(account, false);
                isLoggedIN = true;
                this.dllink = this.getHighesQualityDownloadlinkAltAPI(link, true);
            } else {
                this.dllink = link.getStringProperty(PROPERTY_DIRECTURL);
            }
            this.dllink = this.checkLinkAndSetFilesize(link, this.dllink);
            if (this.dllink == null) {
                /* This will also act as a fallback in case that "original quality" handling fails */
                this.dllink = this.getFreshDirecturl(link, account, isLoggedIN);
                if (this.dllink == null) {
                    /* This should never happen */
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Failed to refresh directurl");
                }
                String ext = null;
                if (ext == null) {
                    ext = getFileNameExtensionFromString(dllink, ".jpg");
                }
                link.setProperty(PROPERTY_DIRECTURL, this.dllink);
                /* Only do this extra request if the user triggered a single linkcheck! */
                if (!isDownload) {
                    this.checkLinkAndSetFilesize(link, this.dllink);
                }
            }
            final String filename = InstaGramComDecrypter.getFilename(link);
            if (filename != null) {
                link.setFinalFileName(filename);
            }
            return AvailableStatus.TRUE;
        }
    }

    private static boolean hasTriedToCrawlOriginalQuality(final DownloadLink link) {
        if (link.hasProperty(PROPERTY_has_tried_to_crawl_original_url)) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isVideo(final DownloadLink link) {
        final String crawlerFilename = link.getStringProperty(PROPERTY_filename_from_crawler);
        if (link.hasProperty(PROPERTY_type)) {
            /* New/current handling */
            final String type = link.getStringProperty(PROPERTY_type);
            if (type.equals("video")) {
                return true;
            } else {
                return false;
            }
        } else if (link.getName() != null && link.getName().endsWith(".mp4")) {
            /* Backward compatibility: TODO: Remove after 01-2023 */
            return true;
        } else if (crawlerFilename != null && crawlerFilename.endsWith(".mp4")) {
            /* Backward compatibility: TODO: Remove after 01-2023 */
            return true;
        } else {
            return false;
        }
    }

    public static boolean isText(final DownloadLink link) {
        if (getType(link).equals("text")) {
            return true;
        } else {
            return false;
        }
    }

    /** @return photo, video, text */
    private static String getType(final DownloadLink link) {
        if (link.hasProperty(PROPERTY_type)) {
            /* New/current handling for items added in revisions > 45677 */
            return link.getStringProperty(PROPERTY_type);
        } else if (isVideo(link)) {
            return "video";
        } else {
            return "photo";
        }
    }

    private static boolean requiresAccount(final DownloadLink link) {
        if (isPartOfStory(link)) {
            return true;
        } else if (isPrivate(link)) {
            return true;
        } else {
            return false;
        }
    }

    private static boolean isPartOfStory(final DownloadLink link) {
        if (link.hasProperty(PROPERTY_is_part_of_story)) {
            return true;
        } else {
            return false;
        }
    }

    private static boolean isPrivate(final DownloadLink link) {
        if (link.hasProperty(PROPERTY_is_private)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Login required to be able to use this!! </br>
     * removePictureEffects true = grab best quality & original, removePictureEffects false = grab best quality but keep effects/filters.
     */
    private String getHighesQualityDownloadlinkAltAPI(final DownloadLink link, final boolean removePictureEffects) throws IOException, PluginException {
        // final String resolution_inside_url = new Regex(dllink, "(/s\\d+x\\d+/)").getMatch(0);
        /*
         * 2017-04-28: By removing the resolution inside the picture URL, we can download the original image - usually, resolution will be
         * higher than before then but it can also get smaller - which is okay as it is the original content.
         */
        /* 2020-10-07: Doesn't work anymore, see new method (old iPhone API endpoint) */
        // String drlink = dllink.replace(resolution_inside_url, "/");
        /* Important: Do not jump into this handling when downloading videos! */
        /*
         * Source of this idea:
         * https://github.com/instaloader/instaloader/blob/f4ecfea64cc11efba44cda2b44c8cfe41adbd28a/instaloader/instaloadercontext.py# L462
         */
        final String imageid = this.getInternalMediaID(link);
        if (imageid == null) {
            /* We need this ID! */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Browser brc = br.cloneBrowser();
        prepBRAltAPI(brc);
        getPageAltAPI(brc, ALT_API_BASE + "/media/" + imageid + "/info/");
        /* Offline errorhandling */
        if (brc.getHttpConnection().getResponseCode() != 200) {
            /* E.g. {"message": "Invalid media_id 1234561234567862322X", "status": "fail"} */
            /* E.g. {"message": "Media not found or unavailable", "status": "fail"} */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /*
         * New URL should be the BEST quality (resolution).
         */
        final Map<String, Object> entries = JSonStorage.restoreFromString(brc.toString(), TypeRef.HASHMAP);
        final Map<String, Object> mediaItem = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "items/{0}");
        final String downloadurl = getBestQualityURLAltAPI(mediaItem);
        link.setProperty(PROPERTY_has_tried_to_crawl_original_url, true);
        link.setProperty(PROPERTY_DIRECTURL, downloadurl);
        return downloadurl;
    }

    public static void getPageAltAPI(final Browser br, final String url) throws PluginException, IOException {
        br.getPage(url);
        checkErrorsAltAPI(br);
    }

    public static void checkErrorsAltAPI(final Browser br) throws PluginException {
        if (br.getHttpConnection().getResponseCode() == 429) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Rate-Limit reached");
        }
    }

    public static String getBestQualityURLAltAPI(final Map<String, Object> entries) {
        final Object videoO = entries.get("video_versions");
        String dllink = null;
        if (videoO != null) {
            /* Video: Find best video-quality */
            /*
             * TODO: 2020-11-17: What's the difference between e.g. the following "types": 101, 102, 103 - seems to be all the same quality
             * and filesize/resolution/URLs
             */
            final List<Object> ressourcelist = (List<Object>) videoO;
            if (ressourcelist != null) {
                long qualityMax = 0;
                for (final Object qualityO : ressourcelist) {
                    final Map<String, Object> imageQualityInfo = (Map<String, Object>) qualityO;
                    final long widthTmp = JavaScriptEngineFactory.toLong(imageQualityInfo.get("width"), 0);
                    if (widthTmp > qualityMax && imageQualityInfo.containsKey("url")) {
                        qualityMax = widthTmp;
                        dllink = (String) imageQualityInfo.get("url");
                    }
                }
            }
        } else {
            /* Image: Find best image-quality */
            final List<Object> ressourcelist = (List<Object>) JavaScriptEngineFactory.walkJson(entries, "image_versions2/candidates");
            if (ressourcelist != null) {
                long qualityMax = 0;
                for (final Object qualityO : ressourcelist) {
                    final Map<String, Object> imageQualityInfo = (Map<String, Object>) qualityO;
                    final long widthTmp = JavaScriptEngineFactory.toLong(imageQualityInfo.get("width"), 0);
                    if (widthTmp > qualityMax && imageQualityInfo.containsKey("url")) {
                        qualityMax = widthTmp;
                        dllink = (String) imageQualityInfo.get("url");
                    }
                }
            }
        }
        final boolean removePictureEffects = true;
        if (dllink != null && removePictureEffects && (dllink.contains("&se=") || dllink.contains("?se="))) {
            /*
             * 2020-10-07: By replacing that one parameter, we will additionally remove all filters so we should get the original picture
             * then! The resolution will usually not change - it will only remove the filters!
             */
            /*
             * Source of this idea:
             * https://github.com/instaloader/instaloader/blob/f4ecfea64cc11efba44cda2b44c8cfe41adbd28a/instaloader/structures.py#L247
             */
            dllink = dllink.replaceAll("&se=\\d+(&)?", "&");
            dllink = dllink.replaceAll("\\?se=\\d+(&)?", "?");
        }
        return dllink;
    }

    private String getFreshDirecturl(final DownloadLink link, final Account account, final boolean isLoggedIN) throws Exception {
        String directurl = null;
        logger.info("Trying to refresh directurl");
        if (preferAltAPIForDirecturlRefresh() && account != null) {
            logger.info("Tring to obtain fresh original quality downloadurl");
            if (!isLoggedIN) {
                this.login(account, false);
            }
            directurl = getHighesQualityDownloadlinkAltAPI(link, true);
        } else if (isPartOfStory(link)) {
            throw new AccountRequiredException("Cannot refresh direct url of story elements without account");
        } else {
            /* More complicated method via crawler */
            logger.info("Trying to obtain fresh downloadurl via crawler");
            final PluginForDecrypt crawler = this.getNewPluginForDecryptInstance(this.getHost());
            final String thisLinkID = this.getLinkID(link);
            final String thisOrderID = link.getStringProperty(PROPERTY_orderid);
            try {
                final CryptedLink forDecrypter = new CryptedLink(link.getContentUrl(), link);
                final ArrayList<DownloadLink> items = crawler.decryptIt(forDecrypter, null);
                DownloadLink foundLink = null;
                for (final DownloadLink linkTmp : items) {
                    if (StringUtils.equals(linkTmp.getLinkID(), thisLinkID)) {
                        foundLink = linkTmp;
                        break;
                    } else if (StringUtils.equals(linkTmp.getStringProperty(PROPERTY_orderid), thisOrderID)) {
                        /* Backward compatibility. TODO: Remove this after 01-2023 */
                        foundLink = linkTmp;
                        break;
                    }
                }
                directurl = foundLink.getStringProperty(PROPERTY_DIRECTURL);
            } catch (final Throwable e) {
                logger.log(e);
            }
        }
        if (directurl == null) {
            /* On failure, check for offline. */
            logger.info("Failed to find fresh directurl --> Assuming that item is offline");
            return null;
        }
        logger.info("Successfully found fresh directurl");
        return directurl;
    }

    private boolean preferAltAPIForDirecturlRefresh() {
        // return PluginJsonConfig.get(InstagramConfig.class).isAttemptToDownloadOriginalQuality();
        /* 2022-03-31: Always use this method as it is way easier. */
        return true;
    }

    public static void checkErrors(final Browser br) throws PluginException {
        /* Old trait */
        // if (br.getURL().matches("https?://[^/]+/accounts/login/\\?next=.*")) {
        /* New trait 2020-11-26 */
        if (br.getURL() != null && br.getURL().matches("https?://[^/]+/accounts/login.*")) {
            throw new AccountRequiredException();
        } else if (br.getURL() != null && br.getURL().matches("https?://[^/]+/challenge/.*")) {
            handleLoginChallenge(br);
        } else if (br.getHttpConnection() != null && br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getHttpConnection() != null && (br.getHttpConnection().getResponseCode() == 403 || br.getHttpConnection().getResponseCode() == 429)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Rate limit reached!");
        }
    }

    private String checkLinkAndSetFilesize(final DownloadLink link, final String flink) throws IOException, PluginException {
        URLConnectionAdapter con = null;
        final Browser br2 = br.cloneBrowser();
        br2.setFollowRedirects(true);
        try {
            con = br2.openHeadConnection(flink);
            if (!looksLikeDownloadableContent(con)) {
                throw new IOException();
            } else {
                if (con.getCompleteContentLength() > 0) {
                    /* 2022-04-05: Don't use con.getCompleteContentLength()! */
                    // link.setVerifiedFileSize(con.getCompleteContentLength());
                    link.setDownloadSize(con.getCompleteContentLength());
                }
                return flink;
            }
        } catch (final Exception e) {
            logger.log(e);
            return null;
        } finally {
            if (con != null) {
                try {
                    con.disconnect();
                } catch (final Exception e) {
                }
            }
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link, null, true);
        handleDownload(link);
    }

    public void handleDownload(final DownloadLink link) throws Exception {
        if (isText(link)) {
            /* Write text to file */
            final File dest = new File(link.getFileOutput());
            IO.writeToFile(dest, link.getStringProperty(PROPERTY_description).getBytes("UTF-8"), IO.SYNC.META_AND_DATA);
            /* Set filesize so user can see it in UI. */
            link.setVerifiedFileSize(dest.length());
            /* Set progress to finished - the "download" is complete. */
            link.getLinkStatus().setStatus(LinkStatus.FINISHED);
        } else {
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final int maxchunks;
            if (isVideo(link)) {
                maxchunks = MAXCHUNKS_videos;
            } else {
                maxchunks = MAXCHUNKS_pictures;
            }
            /*
             * Other User-Agents get throtteled downloadspeed (block by Instagram). For linkchecking we can continue to use the other
             * User-Agents.
             */
            br.getHeaders().put("User-Agent", "curl/7.64.1");
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, this.dllink, RESUME, maxchunks);
            if (!looksLikeDownloadableContent(dl.getConnection())) {
                try {
                    br.followConnection(true);
                } catch (IOException e) {
                    logger.log(e);
                }
                link.removeProperty(PROPERTY_DIRECTURL);
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Directurl expired (?)", 5 * 60 * 1000l);
            }
            dl.startDownload();
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return MAXDOWNLOADS;
    }

    public void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                prepBRWebsite(br);
                final Cookies cookies = account.loadCookies("");
                final Cookies userCookies = account.loadUserCookies();
                if (userCookies != null) {
                    br.setCookies(MAINPAGE, userCookies);
                    if (!force) {
                        logger.info("Trust cookies without check");
                        return;
                    }
                    if (verifyCookies(account, userCookies)) {
                        return;
                    } else {
                        if (account.hasEverBeenValid()) {
                            throw new AccountInvalidException(_GUI.T.accountdialog_check_cookies_expired());
                        } else {
                            throw new AccountInvalidException(_GUI.T.accountdialog_check_cookies_invalid());
                        }
                    }
                }
                if (cookies != null) {
                    br.setCookies(MAINPAGE, cookies);
                    if (!force) {
                        logger.info("Trust cookies without check");
                        return;
                    }
                    if (verifyCookies(account, cookies)) {
                        account.saveCookies(br.getCookies(MAINPAGE), "");
                        return;
                    }
                }
                logger.info("Full login required");
                br.getPage(MAINPAGE + "/");
                final String csrftoken = br.getRegex("\"csrf_token\"\\s*:\\s*\"(.*?)\"").getMatch(0);
                if (csrftoken == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                br.setCookie(MAINPAGE, "csrftoken", csrftoken);
                final String rollout_hash = br.getRegex("\"rollout_hash\"\\s*:\\s*\"(.*?)\"").getMatch(0);
                if (rollout_hash == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final RequestHeader ajaxHeaders = new RequestHeader();
                ajaxHeaders.put("Accept", "*/*");
                ajaxHeaders.put("X-Instagram-AJAX", rollout_hash);
                ajaxHeaders.put("X-CSRFToken", csrftoken);
                ajaxHeaders.put("X-Requested-With", "XMLHttpRequest");
                final PostRequest post = new PostRequest("https://www.instagram.com/accounts/login/ajax/");
                post.setHeaders(ajaxHeaders);
                post.setContentType("application/x-www-form-urlencoded");
                /* 2020-05-19: https://github.com/instaloader/instaloader/pull/623 */
                final String enc_password = "#PWD_INSTAGRAM_BROWSER:0:" + System.currentTimeMillis() + ":" + account.getPass();
                post.setPostDataString("username=" + Encoding.urlEncode(account.getUser()) + "&enc_password=" + Encoding.urlEncode(enc_password) + "&queryParams=%7B%7D");
                br.getPage(post);
                final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
                if (entries.get("status").toString().equals("fail")) {
                    /* 2021-07-13: 2FA login required --> Not implemented so far */
                    final Boolean two_factor_required = (Boolean) entries.get("two_factor_required");
                    if (two_factor_required == null || !two_factor_required) {
                        /* Invalid login credentials */
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else if (!DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                        /*
                         * 2021-07-13: 2FA login not (yet) finished --> Throw error if it is required within login process of a user using
                         * JD outside of IDE.
                         */
                        showCookieLogin2FAWorkaroundInformation();
                        throw new AccountUnavailableException("2-factor-authentication required: Try cookie login method", 30 * 60 * 1000l);
                    }
                    final Map<String, Object> two_factor_info = (Map<String, Object>) entries.get("two_factor_info");
                    if (!(Boolean) two_factor_info.get("sms_two_factor_on")) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "Unsupported 2FA login method (only 2FA SMS is supported)", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                    final DownloadLink dl_dummy;
                    if (this.getDownloadLink() != null) {
                        dl_dummy = this.getDownloadLink();
                    } else {
                        dl_dummy = new DownloadLink(this, "Account", this.getHost(), "https://" + account.getHoster(), true);
                    }
                    String twoFACode = getUserInput("Enter 2-Factor Authentication code for phone number " + two_factor_info.get("obfuscated_phone_number"), dl_dummy);
                    if (twoFACode != null) {
                        twoFACode = twoFACode.trim();
                    }
                    if (twoFACode == null || !twoFACode.matches("\\d+")) {
                        if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiges Format der 2-faktor-Authentifizierung!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        } else {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid 2-factor-authentication code format!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        }
                    }
                    final Form login2 = new Form();
                    login2.setMethod(MethodType.POST);
                    login2.setAction("/accounts/login/ajax/two_factor/");
                    login2.put("identifier", Encoding.urlEncode(two_factor_info.get("two_factor_identifier").toString()));
                    login2.put("trust_signal", "false");
                    login2.put("username", two_factor_info.get("username").toString());
                    login2.put("verificationCode", twoFACode);
                    login2.put("verification_method", "1");
                    // login2.put("queryParams", Encoding.urlEncode("TODO"));
                    br.submitForm(login2);
                    final Map<String, Object> login2Response = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
                    if (!login2Response.get("status").equals("success")) {
                        throw new AccountInvalidException("2-factor-authentication failed");
                    }
                    /*
                     * Old login challenge handling (also unfinished). This is similar to 2FA but more a security measure which Instagram
                     * can trigger at any time. 2FA will only be required if enabled by the user.
                     */
                    // final String page = PluginJSonUtils.getJsonValue(br, "checkpoint_url");
                    // br.getPage(page);
                    // handleLoginChallenge(this.br);
                    // final boolean tryOldChallengeHandling = false;
                    // if (tryOldChallengeHandling) {
                    // // verify by email.
                    // Form f = br.getFormBySubmitvalue("Verify+by+Email");
                    // if (f == null) {
                    // throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    // }
                    // br.submitForm(f);
                    // f = br.getFormBySubmitvalue("Verify+Account");
                    // if (f == null) {
                    // throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    // }
                    // // dialog here to ask for 2factor verification 6 digit code.
                    // final DownloadLink dummyLink = new DownloadLink(null, "Account 2 Factor Auth", MAINPAGE, br.getURL(), true);
                    // final String code = getUserInput("2 Factor Authenication\r\nPlease enter in the 6 digit code within your Instagram
                    // linked email account", dummyLink);
                    // if (code == null) {
                    // throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid 2 Factor response",
                    // PluginException.VALUE_ID_PREMIUM_DISABLE);
                    // }
                    // f.put("response_code", Encoding.urlEncode(code));
                    // // correct or incorrect?
                    // if (br.containsHTML(">Please check the code we sent you and try again\\.<")) {
                    // throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid 2 Factor response",
                    // PluginException.VALUE_ID_PREMIUM_DISABLE);
                    // }
                    // // now 2factor most likely wont have the authenticated json if statement below....
                    // // TODO: confirm what's next.
                    // throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unfinished code, please report issue with logs to
                    // Development Team.");
                    // }
                }
                if (!br.containsHTML("\"authenticated\"\\s*:\\s*true\\s*")) {
                    if (br.containsHTML("\"user\"\\s*:\\s*true\\s*")) {
                        /* {"user":true,"authenticated":false,"status":"ok"} */
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "Sorry, your password was incorrect. Please double-check your password.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(br.getCookies(MAINPAGE), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    protected boolean verifyCookies(final Account account, final Cookies cookies) throws Exception {
        br.setCookies(MAINPAGE, cookies);
        br.getPage(MAINPAGE + "/");
        if (isLoggedIn(br)) {
            /* Saved cookies were valid */
            logger.info("Cookie login successful");
            /* E.g. when user uses cookie login, this cookie should already be included in the imported cookies. */
            final Cookie csrftokenCookie = cookies.get("csrftoken");
            if (csrftokenCookie == null) {
                final String csrftoken = br.getRegex("\"csrf_token\"\\s*:\\s*\"(.*?)\"").getMatch(0);
                if (csrftoken == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                br.setCookie(MAINPAGE, "csrftoken", csrftoken);
            }
            account.saveCookies(br.getCookies(MAINPAGE), "");
            return true;
        } else {
            logger.info("Cookie login failed");
            br.clearCookies(br.getHost());
            return false;
        }
    }

    @Deprecated
    public static void handleLoginChallenge(final Browser br) throws AccountUnavailableException {
        final String json = br.getRegex("window._sharedData = (\\{.*?\\})</script>").getMatch(0);
        if (json != null) {
            Map<String, Object> entries = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
            final String possibleErrormessage = (String) JavaScriptEngineFactory.walkJson(entries, "entry_data/Challenge/{0}/extraData/content/{0}/title");
            if (!DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                /* 2020-10-07: Unfinished code */
                if (!StringUtils.isEmpty(possibleErrormessage)) {
                    throw new AccountUnavailableException("Login challenge required: Complete in browser and try again or try cookie login: " + possibleErrormessage, 5 * 60 * 1000l);
                } else {
                    throw new AccountUnavailableException("Login challenge required: Complete in browser and try again or try cookie login", 5 * 60 * 1000l);
                }
            }
            // final List<Object> challenges = (List<Object>) JavaScriptEngineFactory.walkJson(entries, "entry_data/Challenge");
            // boolean foundKnownChallenge = false;
            // for (final Object challengeO : challenges) {
            // entries = (Map<String, Object>) challengeO;
            // final String challengeType = (String) entries.get("challengeType");
            // if (StringUtils.isEmpty(challengeType)) {
            // continue;
            // }
            // if (challengeType.equalsIgnoreCase("SelectVerificationMethodForm")) {
            // foundKnownChallenge = true;
            // /* Take the simplest way: Auto-select first option and ask user for verification code */
            // entries = (Map<String, Object>) entries.get("fields");
            // /* Assume it's mail verification */
            // final String email = (String) entries.get("email");
            // if (StringUtils.isEmpty(email)) {
            // throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            // }
            // PostRequest loginChoiceRequest = new PostRequest(br.getURL());
            // loginChoiceRequest.setHeaders(ajaxHeaders);
            // loginChoiceRequest.setContentType("application/x-www-form-urlencoded");
            // post.setPostDataString("choice=1");
            // br.getPage(loginChoiceRequest);
            // entries = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
            // final Object twoFaTextO = JavaScriptEngineFactory.walkJson(entries, "extraData/content/{1}/text");
            // final String twoFaText;
            // if (twoFaTextO != null && twoFaTextO instanceof String) {
            // twoFaText = (String) twoFaTextO;
            // } else {
            // twoFaText = "2 Factor Authenication\r\nPlease enter in the 6 digit code within your Instagram linked email account";
            // }
            // final DownloadLink dummyLink = new DownloadLink(null, "Account 2 Factor Auth", MAINPAGE, br.getURL(), true);
            // final String code = getUserInput(twoFaText, dummyLink);
            // if (code == null) {
            // throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid 2 Factor response format",
            // PluginException.VALUE_ID_PREMIUM_DISABLE);
            // }
            // post.setPostDataString("security_code=" + code);
            // br.getPage(post);
            // entries = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
            // final String status = (String) entries.get("status");
            // if (!"success".equalsIgnoreCase(status)) {
            // throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\n2FA login failed", PluginException.VALUE_ID_PREMIUM_DISABLE);
            // }
            // /* TODO: Fully implement this */
            // } else {
            // /* Unknown challenge-type */
            // }
            // }
            // if (!foundKnownChallenge) {
            // throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUnknown login challenge: Try cookie login",
            // PluginException.VALUE_ID_PREMIUM_DISABLE);
            // }
        }
    }

    private Thread showCookieLogin2FAWorkaroundInformation() {
        final Thread thread = new Thread() {
            public void run() {
                try {
                    final String help_article_url = "https://support.jdownloader.org/Knowledgebase/Article/View/account-cookie-login-instructions";
                    String message = "";
                    final String title;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        title = "Instagram - Login";
                        message += "Hallo liebe(r) Instagram NutzerIn\r\n";
                        message += "Instagram verlangt eine 2-Faktor-Authentifizierung für diesen Account. JDownloader unterstützt diesen Login-Typ nicht.\r\n";
                        message += "Versuche bitte folgende alternative login Methode:\r\n";
                        message += help_article_url;
                    } else {
                        title = "Instagram - Login";
                        message += "Hello dear Instagram user\r\n";
                        message += "Instagram is asking for a 2-factor-authentication for your account. JDownloader does not support this login method.\r\n";
                        message += "Try the following alternative login method as a workaround:\r\n";
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

    /** Checks loggedin state based on html code (NOT cookies!) */
    private boolean isLoggedIn(final Browser br) {
        // return br.getCookies(MAINPAGE).get("sessionid", Cookies.NOTDELETEDPATTERN) != null && br.getCookies(MAINPAGE).get("ds_user_id",
        // Cookies.NOTDELETEDPATTERN) != null;
        final String fullname = PluginJSonUtils.getJson(br, "full_name");
        final String has_profile_pic = PluginJSonUtils.getJson(br, "has_profile_pic");
        if (fullname != null && has_profile_pic != null) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        synchronized (account) {
            login(account, true);
        }
        final AccountInfo ai = new AccountInfo();
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        account.setConcurrentUsePossible(true);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link, account, true);
        this.handleDownload(link);
    }

    public static Browser prepBRWebsite(final Browser br) {
        br.getHeaders().put("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36");
        br.setCookie(MAINPAGE, "ig_pr", "1");
        // 429 == too many requests, we need to rate limit requests.
        br.setAllowedResponseCodes(new int[] { 400, 429 });
        br.setFollowRedirects(true);
        return br;
    }

    @Override
    public Class<? extends InstagramConfig> getConfigInterface() {
        return InstagramConfig.class;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
