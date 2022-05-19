//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
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

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.SubConfiguration;
import jd.controlling.AccountController;
import jd.controlling.linkcrawler.CrawledLink;
import jd.http.Browser;
import jd.http.Browser.BrowserException;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.SimpleFTP;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.components.UserAgents;
import jd.plugins.components.UserAgents.BrowserName;
import jd.plugins.decrypter.VKontakteRu;
import jd.utils.JDUtilities;
import jd.utils.locale.JDL;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.storage.config.annotations.LabelInterface;
import org.appwork.utils.Files;
import org.appwork.utils.Hash;
import org.appwork.utils.StringUtils;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.logging.LogController;
import org.jdownloader.plugins.SkipReasonException;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.scripting.JavaScriptEngineFactory;

//Links are coming from a decrypter
@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "vk.com" }, urls = { "https?://vkontaktedecrypted\\.ru/(picturelink/(?:-)?\\d+_\\d+(\\?tag=[\\d\\-]+)?|audiolink/(?:-)?\\d+_\\d+)|https?://(?:new\\.)?vk\\.com/(doc[\\d\\-]+_[\\d\\-]+|s/v1/doc/[A-Za-z0-9\\-_]+|video[\\d\\-]+_[\\d\\-]+(?:#quality=\\d+p)?)(\\?hash=[^&#]+(\\&dl=[^&#]{16,})?)?|https?://(?:c|p)s[a-z0-9\\-]+\\.(?:vk\\.com|userapi\\.com|vk\\.me|vkuservideo\\.net|vkuseraudio\\.net)/[^<>\"]+\\.(?:mp[34]|(?:rar|zip|pdf).+|[rz][0-9]{2}.+)" })
public class VKontakteRuHoster extends PluginForHost {
    /* Current main domain */
    private static final String DOMAIN                                                                      = "vk.com";
    private static final String TYPE_AUDIOLINK                                                              = "https?://vkontaktedecrypted\\.ru/audiolink/((?:\\-)?\\d+)_(\\d+)";
    /* TODO: Remove this */
    private static final String TYPE_VIDEOLINK_LEGACY                                                       = "(?i)https?://vkontaktedecrypted\\.ru/videolink/.+";
    private static final String TYPE_VIDEOLINK                                                              = "(?i)https?://[^/]+/video([\\d\\-]+)_([\\d\\-]+)(#quality=(\\d+p|hls))?";
    private static final String TYPE_DIRECT                                                                 = "(?i)https?://(?:c|p)s[a-z0-9\\-]+\\.(?:vk\\.com|userapi\\.com|vk\\.me|vkuservideo\\.net|vkuseraudio\\.net)/[^<>\"]+\\.(?:[A-Za-z0-9]{1,5})(?:.*)";
    private static final String TYPE_PICTURELINK                                                            = "(?i)https?://vkontaktedecrypted\\.ru/picturelink/((?:\\-)?\\d+)_(\\d+)(\\?tag=[\\d\\-]+)?";
    public static final String  TYPE_DOCLINK_1                                                              = "(?i)https?://[^/]+/doc([\\d\\-]+)_([\\d\\-]+)(\\?hash=[^&#]+(\\&dl=[^&#]{16,})?)?";
    public static final String  TYPE_DOCLINK_2                                                              = "(?i)https?://[^/]+/s/v1/doc/([A-Za-z0-9\\-_]+)";
    public static final long    trust_cookie_age                                                            = 300000l;
    private static final String TEMPORARILYBLOCKED                                                          = jd.plugins.decrypter.VKontakteRu.TEMPORARILYBLOCKED;
    /* Settings stuff */
    public static final String  FASTLINKCHECK_VIDEO                                                         = "FASTLINKCHECK_VIDEO";
    public static final String  FASTCRAWL_VIDEO                                                             = "FASTCRAWL_VIDEO";
    private static final String FASTLINKCHECK_PICTURES                                                      = "FASTLINKCHECK_PICTURES_V2";
    private static final String FASTLINKCHECK_AUDIO                                                         = "FASTLINKCHECK_AUDIO";
    public static final String  VIDEO_QUALITY_SELECTION_MODE                                                = "VIDEO_QUALITY_SELECTION_MODE";
    public static final String  PREFERRED_VIDEO_QUALITY                                                     = "PREFERRED_VIDEO_QUALITY";
    public static final String  VIDEO_ADD_NAME_OF_UPLOADER_TO_FILENAME                                      = "VIDEO_ADD_NAME_OF_UPLOADER_TO_FILENAME";
    private static final String VKWALL_GRAB_ALBUMS                                                          = "VKWALL_GRAB_ALBUMS";
    private static final String VKWALL_GRAB_PHOTOS                                                          = "VKWALL_GRAB_PHOTOS";
    private static final String VKWALL_GRAB_AUDIO                                                           = "VKWALL_GRAB_AUDIO";
    private static final String VKWALL_GRAB_VIDEO                                                           = "VKWALL_GRAB_VIDEO";
    private static final String VKWALL_GRAB_URLS                                                            = "VKWALL_GRAB_URLS";
    public static final String  VKWALL_GRAB_DOCS                                                            = "VKWALL_GRAB_DOCS";
    public static final String  VKWALL_GRAB_URLS_INSIDE_POSTS                                               = "VKWALL_GRAB_URLS_INSIDE_POSTS";
    public static final String  VKWALL_GRAB_URLS_INSIDE_POSTS_REGEX                                         = "VKWALL_GRAB_URLS_INSIDE_POSTS_REGEX";
    public static final String  VKWALL_GRAB_COMMENTS_PHOTOS                                                 = "VKWALL_GRAB_COMMENTS_PHOTOS";
    public static final String  VKWALL_GRAB_COMMENTS_AUDIO                                                  = "VKWALL_GRAB_COMMENTS_AUDIO";
    public static final String  VKWALL_GRAB_COMMENTS_VIDEO                                                  = "VKWALL_GRAB_COMMENTS_VIDEO";
    public static final String  VKWALL_GRAB_COMMENTS_URLS                                                   = "VKWALL_GRAB_COMMENTS_URLS";
    public static final String  VKVIDEO_ALBUM_USEIDASPACKAGENAME                                            = "VKVIDEO_ALBUM_USEIDASPACKAGENAME";
    public static final String  VKVIDEO_USEIDASPACKAGENAME                                                  = "VKVIDEO_USEIDASPACKAGENAME";
    private static final String VKAUDIOS_USEIDASPACKAGENAME                                                 = "VKAUDIOS_USEIDASPACKAGENAME";
    private static final String VKDOCS_USEIDASPACKAGENAME                                                   = "VKDOCS_USEIDASPACKAGENAME";
    private static final String VKDOCS_ADD_UNIQUE_ID                                                        = "VKDOCS_ADD_UNIQUE_ID";
    private static final String VKPHOTOS_TEMP_SERVER_FILENAME_AS_FINAL_FILENAME                             = "VKPHOTOS_TEMP_SERVER_FILENAME_AS_FINAL_FILENAME";
    private static final String VKPHOTOS_TEMP_SERVER_FILENAME_AND_OWNER_ID_AND_CONTENT_ID_AS_FINAL_FILENAME = "VKPHOTOS_TEMP_SERVER_FILENAME_AND_OWNER_ID_AND_CONTENT_ID_AS_FINAL_FILENAME";
    private static final String VKPHOTO_CORRECT_FINAL_LINKS                                                 = "VKPHOTO_CORRECT_FINAL_LINKS";
    public static final String  VKWALL_USE_API                                                              = "VKWALL_USE_API_2019_07";
    public static final String  VKWALL_STORE_PICTURE_DIRECTURLS                                             = "VKWALL_STORE_PICTURE_DIRECTURLS";
    public static final String  VKWALL_STORE_PICTURE_DIRECTURLS_PREFER_STORED_DIRECTURLS                    = "VKWALL_STORE_PICTURE_DIRECTURLS_PREFER_STORED_DIRECTURLS";
    public static final String  VKADVANCED_USER_AGENT                                                       = "VKADVANCED_USER_AGENT";
    /* html patterns */
    public static final String  HTML_VIDEO_REMOVED_FROM_PUBLIC_ACCESS                                       = "This video has been removed from public access";
    public static Object        LOCK                                                                        = new Object();
    private String              finalUrl                                                                    = null;
    private String              ownerID                                                                     = null;
    private String              contentID                                                                   = null;
    private static final String ALPHANUMERIC                                                                = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN0PQRSTUVWXYZO123456789+/=";
    private String              vkID                                                                        = null;
    /* Properties */
    /* General */
    public static final String  PROPERTY_GENERAL_owner_id                                                   = "owner_id";
    public static final String  PROPERTY_GENERAL_content_id                                                 = "content_id";
    public static String        PROPERTY_GENERAL_TITLE_PLAIN                                                = "title_plain";
    public static String        PROPERTY_GENERAL_DATE                                                       = "date";
    public static String        PROPERTY_GENERAL_UPLOADER                                                   = "uploader";
    /* Can be given for any content if it is part of a wall post */
    public static String        PROPERTY_GENERAL_wall_post_id                                               = "wall_post_id";
    /* For single photos */
    public static final String  PROPERTY_PHOTOS_picturedirectlink                                           = "picturedirectlink";
    public static final String  PROPERTY_PHOTOS_directurls_fallback                                         = "directurls_fallback";
    public static final String  PROPERTY_PHOTOS_photo_list_id                                               = "photo_list_id";
    public static final String  PROPERTY_PHOTOS_photo_module                                                = "photo_module";
    public static final String  PROPERTY_PHOTOS_album_id                                                    = "albumid";
    /* For single audio items */
    public static final String  PROPERTY_AUDIO_special_id                                                   = "audio_special_id";
    /* For single video items */
    // public static String PROPERTY_VIDEO_CRAWLMODE = "video_crawlmode";
    // public static String PROPERTY_VIDEO_PREFERRED_QUALITY = "video_preferred_quality";
    public static String        PROPERTY_VIDEO_LIST_ID                                                      = "video_list_id";
    public static String        PROPERTY_VIDEO_SELECTED_QUALITY                                             = "selectedquality";

    public VKontakteRuHoster(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium();
        this.setConfigElements();
    }

    @Override
    public void init() {
        setRequestIntervalLimits();
    }

    public static void setRequestIntervalLimits() {
        Browser.setBurstRequestIntervalLimitGlobal("vk.com", 500, 15, 30000);
    }

    public boolean allowHandle(final DownloadLink link, final PluginForHost plugin) {
        /* 2019-08-06: Do not allow multihost plugins to handle URLs handled by this plugin! */
        return link.getHost().equalsIgnoreCase(plugin.getHost());
    }

    @Override
    public CrawledLink convert(DownloadLink link) {
        final CrawledLink ret = super.convert(link);
        final String url = link.getPluginPatternMatcher();
        if (url != null && url.matches(TYPE_DIRECT)) {
            setAndGetFileNameFromDirectURL(link, url);
        }
        return ret;
    }

    private String setAndGetFileNameFromDirectURL(DownloadLink link, final String url) {
        String filename = extractFileNameFromURL(url);
        if (filename != null) {
            final String extension = Files.getExtension(filename);
            if (StringUtils.endsWithCaseInsensitive(filename, "." + extension)) {
                final String urlName = new Regex(url, ".+/([^/].+\\." + Pattern.quote(extension) + ")$").getMatch(0);
                if (urlName != null) {
                    filename = urlName;
                }
            }
            try {
                final String urlDecoded = SimpleFTP.BestEncodingGuessingURLDecode(filename);
                if (link != null) {
                    link.setFinalFileName(urlDecoded);
                }
                return urlDecoded;
            } catch (final Throwable e) {
                if (link != null) {
                    link.setName(filename);
                }
                return filename;
            }
        }
        return null;
    }

    @Override
    public String rewriteHost(String host) {
        if (host == null || host.equalsIgnoreCase("vkontakte.ru")) {
            return this.getHost();
        } else {
            return super.rewriteHost(host);
        }
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        return requestFileInformation(link, account, false);
    }

    @SuppressWarnings("deprecation")
    public AvailableStatus requestFileInformation(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        // nullify previous
        br = new Browser();
        dl = null;
        finalUrl = null;
        // Initialise
        int checkstatus = 0;
        // setters
        prepBrowser(br, false);
        setConstants(link);
        if (link.getPluginPatternMatcher().matches(TYPE_DIRECT)) {
            finalUrl = link.getPluginPatternMatcher();
            /* Prefer filename inside url */
            final String filename = extractFileNameFromURL(finalUrl);
            if (filename != null) {
                link.setFinalFileName(filename);
            }
            checkstatus = linkOk(link, isDownload);
            if (checkstatus != 1) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        } else {
            if (account != null) {
                /* Always login if possible (except for downloading directurls) */
                logger.info("Account available during availablecheck");
                login(br, account, false);
            } else {
                logger.info("Account not available during availablecheck");
            }
            if (VKontakteRu.isTypeDocument(link.getPluginPatternMatcher())) {
                if (!link.isNameSet() && this.ownerID != null && this.contentID != null) {
                    /* Set fallback filename */
                    link.setName("doc" + this.ownerID + "_" + this.contentID + ".pdf");
                }
                br.setFollowRedirects(false);
                br.getPage(link.getPluginPatternMatcher());
                handleTooManyRequests(this, br);
                final String redirect = br.getRedirectLocation();
                if (redirect != null && redirect.matches(TYPE_DIRECT)) {
                    /* Check if we got a directURL. */
                    finalUrl = redirect;
                    setAndGetFileNameFromDirectURL(link, finalUrl);
                    return AvailableStatus.TRUE;
                }
                if (br.containsHTML("(?i)This document is available only to its owner\\.")) {
                    throw new AccountRequiredException("This document is available only to its owner");
                }
                final String json = br.getRegex("Docs\\.initDoc\\((\\{.*?\\})\\);").getMatch(0);
                if (json == null) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                final Map<String, Object> doc = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
                finalUrl = (String) doc.get("docUrl");
                if (StringUtils.isEmpty(finalUrl)) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                if (link.getPluginPatternMatcher().matches(TYPE_DOCLINK_2)) {
                    /* Het IDs from json as they're not provided inside given URL. */
                    this.ownerID = doc.get("docOwnerId").toString();
                    this.contentID = doc.get("docId").toString();
                    link.setProperty(PROPERTY_GENERAL_owner_id, this.ownerID);
                    link.setProperty(PROPERTY_GENERAL_content_id, this.contentID);
                }
                final String filenameFromHTML = br.getRegex("<title>([^<>\"]*?)</title>").getMatch(0);
                /* Sometimes filenames on site are cut - finallink usually contains the full filenames */
                final String betterFilename = new Regex(finalUrl, "/([^<>\"/]+)\\?extra=.+$").getMatch(0);
                String title = null;
                if (betterFilename != null) {
                    title = Encoding.htmlDecode(betterFilename).trim();
                } else if (filenameFromHTML != null) {
                    title = Encoding.htmlDecode(filenameFromHTML).trim();
                }
                if (title != null) {
                    final String fileExtension = (String) doc.get("docExt");
                    title = this.correctOrApplyFileNameExtension(title, "." + fileExtension);
                    if (this.getPluginConfig().getBooleanProperty(VKDOCS_ADD_UNIQUE_ID, default_VKDOCS_ADD_UNIQUE_ID)) {
                        link.setFinalFileName("doc" + this.ownerID + "_" + this.contentID + "_" + title);
                    } else {
                        link.setFinalFileName(title);
                    }
                }
                checkstatus = linkOk(link, isDownload);
                if (checkstatus != 1) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            } else if (link.getPluginPatternMatcher().matches(VKontakteRuHoster.TYPE_AUDIOLINK)) {
                finalUrl = link.getStringProperty("directlink", null);
                if (!audioIsValidDirecturl(finalUrl)) {
                    checkstatus = 0;
                } else {
                    checkstatus = linkOk(link, isDownload);
                }
                if (checkstatus != 1) {
                    if (this.isDRMProtected(link)) {
                        return AvailableStatus.UNCHECKABLE;
                    }
                    String url = null;
                    final Browser br = this.br.cloneBrowser();
                    br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                    /*
                     * If these two values are present, we know that the content initially came from a 'wall' which requires us to use a
                     * different method to grab it as without that, permissions to play the track might be missing as it can only be
                     * accessed inside that particular wall!
                     */
                    final String postID = link.getStringProperty("postID", null);
                    final String fromId = link.getStringProperty("fromId", null);
                    boolean failed = false;
                    if (postID != null && fromId != null) {
                        logger.info("Trying to refresh audiolink directlink via wall-handling");
                        final String post = "act=get_wall_playlist&al=1&local_id=" + postID + "&oid=" + fromId + "&wall_type=own";
                        br.postPage(getBaseURL() + "/audio", post);
                        url = br.getRegex("\"0\":\"" + Pattern.quote(ownerID) + "\",\"1\":\"" + Pattern.quote(contentID) + "\",\"2\":(\"[^\"]+\")").getMatch(0);
                        if (url == null) {
                            /* Try other way below. */
                            failed = true;
                        } else {
                            /* Decodes the json string */
                            url = (String) JavaScriptEngineFactory.jsonToJavaObject(url);
                        }
                    } else {
                        failed = true;
                    }
                    if (failed) {
                        logger.info("refreshing audiolink directlink via album-handling");
                        /*
                         * No way to easily get the needed info directly --> Load the complete audio album and find a fresh directlink for
                         * our ID.
                         *
                         * E.g. get-play-link: https://vk.com/audio?id=<ownerID>&audio_id=<contentID>
                         */
                        /*
                         * 2017-01-05: They often change the order of the ownerID and contentID parameters here so from now on, let's try
                         * both variants.
                         */
                        postPageSafe(account, link, getBaseURL() + "/al_audio.php", "act=reload_audio&al=1&ids=" + ownerID + "_" + contentID);
                        url = audioGetDirectURL();
                        if (url == null) {
                            postPageSafe(account, link, getBaseURL() + "/al_audio.php", "act=reload_audio&al=1&ids=" + contentID + "_" + ownerID);
                            url = audioGetDirectURL();
                            if (url == null) {
                                postPageSafe(account, link, getBaseURL() + "/al_audio.php", "act=reload_audio&al=1&ids=" + ownerID + "_" + contentID);
                                url = audioGetDirectURL();
                            }
                        }
                    }
                    if (url == null) {
                        if (failed) {
                            /*
                             * 2017-01-05: Changed from ERROR_FILE_NOT_FOUND to ERROR_TEMPORARILY_UNAVAILABLE --> Until now we never had a
                             * good test case to identify offline urls.
                             */
                            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server issue - content might be offline", 5 * 60 * 1000l);
                        } else {
                            logger.warning("Failed to refresh audiolink directlink");
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        }
                    }
                    finalUrl = url;
                    checkstatus = linkOk(link, isDownload);
                    if (checkstatus != 1) {
                        logger.info("Refreshed audiolink directlink seems not to work --> Link is probably offline");
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    } else {
                        link.setProperty("directlink", finalUrl);
                    }
                }
            } else if (this.isTypeVideo(link.getPluginPatternMatcher())) {
                br.setFollowRedirects(true);
                finalUrl = link.getStringProperty("directlink");
                /* Don't lose filenames if e.g. user resets DownloadLink. */
                final String linkQuality = link.getStringProperty(VKontakteRuHoster.PROPERTY_VIDEO_SELECTED_QUALITY);
                if (linkQuality != null) {
                    link.setFinalFileName(link.getStringProperty(VKontakteRuHoster.PROPERTY_GENERAL_TITLE_PLAIN) + "_" + this.getOwnerID(link) + "_" + this.getContentID(link) + "_" + linkQuality + ".mp4");
                }
                /* Check if directlink is expired */
                checkstatus = linkOk(link, isDownload);
                if (checkstatus != 1) {
                    /* Refresh directlink */
                    accessVideo(this, this.br, link.getPluginPatternMatcher(), this.ownerID, this.contentID, link.getStringProperty(VKontakteRuHoster.PROPERTY_VIDEO_LIST_ID));
                    if (br.containsHTML(VKontakteRuHoster.HTML_VIDEO_REMOVED_FROM_PUBLIC_ACCESS)) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    } else if (br.containsHTML("class\\s*=\\s*\"message_page_body\">\\s*Access denied")) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                    final Map<String, Object> video = jd.plugins.decrypter.VKontakteRu.findVideoMap(this.br, this.contentID);
                    final Map<String, String> availableQualities = jd.plugins.decrypter.VKontakteRu.findAvailableVideoQualities(video);
                    if (availableQualities.isEmpty()) {
                        /* This should never happen */
                        logger.info("vk.com: Couldn't find any available qualities for videolink");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    if (StringUtils.isNotEmpty(linkQuality)) {
                        final Map<String, String> selectedQualities = VKontakteRu.getSelectedVideoQualities(availableQualities, QualitySelectionMode.ALL, null);
                        finalUrl = selectedQualities.get(linkQuality);
                        if (finalUrl == null) {
                            if (selectedQualities.isEmpty()) {
                                throw new PluginException(LinkStatus.ERROR_FATAL, "Failed to refresh directurl - Content offline?");
                            } else {
                                logger.info("Qualities:" + selectedQualities);
                                /* Rare case: User has to delete- and re-add URL via crawler. */
                                throw new PluginException(LinkStatus.ERROR_FATAL, "Selected quality is not available:ALL|" + linkQuality);
                            }
                        }
                    } else {
                        final QualitySelectionMode mode = QualitySelectionMode.values()[link.getIntegerProperty(VIDEO_QUALITY_SELECTION_MODE, default_VIDEO_QUALITY_SELECTION_MODE)];
                        final Quality quality = Quality.values()[link.getIntegerProperty(PREFERRED_VIDEO_QUALITY, default_PREFERRED_VIDEO_QUALITY)];
                        Map<String, String> selectedQualities = VKontakteRu.getSelectedVideoQualities(availableQualities, mode, quality.getLabel());
                        if (selectedQualities.isEmpty()) {
                            selectedQualities = VKontakteRu.getSelectedVideoQualities(availableQualities, QualitySelectionMode.ALL, null);
                            if (selectedQualities.isEmpty()) {
                                throw new PluginException(LinkStatus.ERROR_FATAL, "Failed to refresh directurl - Content offline?");
                            } else {
                                logger.info("Qualities:" + selectedQualities);
                                /* Rare case: User has to delete- and re-add URL via crawler. */
                                throw new PluginException(LinkStatus.ERROR_FATAL, "Selected quality is not available:" + mode + "|" + quality);
                            }
                        }
                        /* Assume map only contains this one element */
                        final Entry<String, String> entry = selectedQualities.entrySet().iterator().next();
                        finalUrl = entry.getValue();
                        /*
                         * Set this quality which will also update the linkID of this DownloadLink so user cannot add the same quality
                         * multiple times.
                         */
                        link.setProperty(VKontakteRuHoster.PROPERTY_VIDEO_SELECTED_QUALITY, entry.getKey());
                        /* Correct filename which did not contain any quality modifier before. */
                        link.setFinalFileName(link.getStringProperty(VKontakteRuHoster.PROPERTY_GENERAL_TITLE_PLAIN) + "_" + this.getOwnerID(link) + "_" + this.getContentID(link) + "_" + entry.getKey() + ".mp4");
                    }
                }
            } else {
                /* Single photo --> Complex handling */
                this.finalUrl = link.getStringProperty(PROPERTY_PHOTOS_picturedirectlink);
                if (this.finalUrl == null && this.getPluginConfig().getBooleanProperty(VKWALL_STORE_PICTURE_DIRECTURLS, default_VKWALL_STORE_PICTURE_DIRECTURLS) && this.getPluginConfig().getBooleanProperty(VKWALL_STORE_PICTURE_DIRECTURLS_PREFER_STORED_DIRECTURLS, default_VKWALL_STORE_PICTURE_DIRECTURLS_PREFER_STORED_DIRECTURLS)) {
                    this.finalUrl = getHighestQualityPicFromSavedJson(link, link.getStringProperty(PROPERTY_PHOTOS_directurls_fallback, null), isDownload);
                }
                if (this.finalUrl == null) {
                    String photo_list_id = link.getStringProperty(PROPERTY_PHOTOS_photo_list_id);
                    final String module = link.getStringProperty(PROPERTY_PHOTOS_photo_module);
                    final String photoID = getPhotoID(link);
                    setHeadersPhoto(br);
                    if (module != null) {
                        /* Access photo inside wall-post or qwall reply or photo album */
                        if (photo_list_id == null) {
                            photo_list_id = "";
                        }
                        int photo_counter = 0;
                        do {
                            photo_counter++;
                            if (photo_counter > 1) {
                                /* 2nd loop */
                                /*
                                 * 2020-01-28: Some content needs a photo_list_id which is generated when accessing the html page so this
                                 * can be seen as a workaround but sometimes it is mandatory!
                                 */
                                final String content_url = link.getContentUrl();
                                if (content_url == null || !content_url.contains(photoID)) {
                                    logger.info("photo_list_id workaround is not possible");
                                    break;
                                }
                                logger.info("Attempting photo_list_id workaround");
                                this.getPageSafe(account, link, content_url);
                                final String new_photo_list_id = br.getRegex(photoID + "(?:%2F|/)([a-f0-9]+)").getMatch(0);
                                if (new_photo_list_id == null) {
                                    logger.warning("photo_list_id workaround failed");
                                    if (account == null) {
                                        /* Assume that account is required */
                                        throw new AccountRequiredException();
                                    } else {
                                        /* Assume that permissions are missing. */
                                        throw new AccountRequiredException("Missing permissions");
                                    }
                                } else {
                                    logger.warning("Successfully found new photo_list_id: " + new_photo_list_id);
                                    photo_list_id = new_photo_list_id;
                                }
                            }
                            /*
                             * 2020-01-27: Browser request would also contain "&dmcah=".
                             */
                            final String postData = "act=show&al=1&al_ad=0&dmcah=&list=" + photo_list_id + "&module=" + module + "" + "&photo=" + photoID;
                            postPageSafe(br, account, link, getBaseURL() + "/al_photos.php?act=show", postData);
                        } while (photo_counter <= 1 && PluginJSonUtils.unescape(br.toString()).contains("\"Access denied\""));
                        checkErrorsPhoto(br);
                    } else {
                        /* Access normal photo / photo inside album */
                        String albumID = link.getStringProperty(PROPERTY_PHOTOS_album_id);
                        boolean jsonSourceAvailableFromHtml = false;
                        if (albumID == null) {
                            /* Find albumID */
                            getPageSafe(account, link, getBaseURL() + "/photo" + photoID);
                            if (br.containsHTML(">\\s*(Unknown error|Unbekannter Fehler|Access denied|Error<)") || this.br.getHttpConnection().getResponseCode() == 404) {
                                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                            } else if (!br.getURL().contains(photoID)) {
                                /*
                                 * E.g. redirect to somewhere else e.g. single post/wall -> That might be online but we've failed to find
                                 * that specific picture-ID.
                                 */
                                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                            }
                            albumID = br.getRegex("class=\"active_link\">[\t\n\r ]+<a href=\"/(.*?)\"").getMatch(0);
                            if (albumID == null) { /* new.vk.com */
                                albumID = br.getRegex("<span class=\"photos_album_info\"><a href=\"/(.*?)\\?.*?\"").getMatch(0);
                            }
                            if (albumID == null) {
                                /* New 2016-08-23 */
                                final String jsonInsideHTML = this.br.getRegex("ajax\\.preload\\(\\'al_photos\\.php\\'\\s*?,\\s*?(\\{.*?)\\);").getMatch(0);
                                if (jsonInsideHTML != null) {
                                    String albumIDFromJson = PluginJSonUtils.getJsonValue(jsonInsideHTML, "list");
                                    if (!StringUtils.isEmpty(albumIDFromJson)) {
                                        /* Fix id */
                                        albumIDFromJson = albumIDFromJson.replace("album", "");
                                        /* Set ID */
                                        albumID = albumIDFromJson;
                                    }
                                }
                            }
                            if (albumID != null) {
                                /* Save this! Important! */
                                link.setProperty(PROPERTY_PHOTOS_album_id, albumID);
                            }
                            if (picturesGetJsonFromHtml(br) != null) {
                                jsonSourceAvailableFromHtml = true;
                            }
                        }
                        if (!jsonSourceAvailableFromHtml) {
                            /* Only go the json-way if we have to! */
                            if (albumID == null) {
                                logger.info("albumID is null and failed to find picture json");
                                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                            }
                            postPageSafe(br, account, link, getBaseURL() + "/al_photos.php", "act=show&al=1&module=photos&list=album" + albumID + "&photo=" + photoID);
                            checkErrorsPhoto(br);
                        }
                    }
                    try {
                        this.finalUrl = getHighestQualityPictureDownloadurl(br, link, isDownload);
                        if (StringUtils.isEmpty(this.finalUrl)) {
                            /* Fallback but this will only work if the user enabled a specified plugin setting. */
                            this.finalUrl = getHighestQualityPicFromSavedJson(link, link.getStringProperty(PROPERTY_PHOTOS_directurls_fallback, null), isDownload);
                        }
                        if (StringUtils.isEmpty(this.finalUrl) && link.getStringProperty(PROPERTY_PHOTOS_directurls_fallback, null) == null) {
                            /* 2019-08-08: Just a hint */
                            logger.info("Possible failure - as a workaround download might be possible via: Enable plugin setting PROPERTY_directurls_fallback --> Re-add downloadurls --> Try again");
                        }
                    } catch (final Throwable e) {
                        logger.info("Error occured while trying to find highest quality image downloadurl");
                        logger.log(e);
                    }
                }
                /* 2016-10-07: Implemented to avoid host-side block although results tell me that this does not improve anything. */
                setHeaderRefererPhoto(this.br);
                final String temp_name = photoGetFinalFilename(getPhotoID(link), null, this.finalUrl);
                if (isDownload && temp_name != null) {
                    link.setFinalFileName(temp_name);
                } else if (!isDownload && temp_name != null) {
                    link.setName(temp_name);
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    /** Check errors which may happen after POST '/al_photos.php' request. */
    private void checkErrorsPhoto(final Browser br) throws PluginException {
        if (br.containsHTML(">\\s*Unfortunately, this photo has been deleted")) {
            /* html */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML(">\\s*Access denied")) {
            /* html */
            throw new AccountRequiredException();
        } else if (br.containsHTML("Access denied\\\\\"") || br.containsHTML("Access denied\"")) {
            /* json */
            /*
             * 2019-10-02: E.g.
             * {"payload":["8",["\"Access denied\"","false","\"12345678\""]],"loaderVersion":"12345678","pageviewCandidate":true,"langPack":
             * 3,"langVersion":"4268"}
             */
            /*
             * json version. Access denied is not exactly offline but usually it is kind of impossible to know which rights are required to
             * be able to get read-permissions for such files! We'll handle it as "Account required".
             */
            throw new AccountRequiredException();
        }
    }

    public void handleDownload(final DownloadLink link, final Account account) throws Exception, PluginException {
        requestFileInformation(link, account, true);
        if (this.isDRMProtected(link)) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "DRM protected");
        }
        if (this.isHLS(link, this.finalUrl)) {
            /* HLS download */
            br.getPage(this.finalUrl);
            handleTooManyRequests(this, br);
            final HlsContainer hlsbest = HlsContainer.findBestVideoByBandwidth(HlsContainer.getHlsQualities(this.br));
            checkFFmpeg(link, "Download a HLS Stream");
            dl = new HLSDownloader(link, br, hlsbest.getDownloadurl());
            dl.startDownload();
        } else {
            if (dl == null) {
                if (StringUtils.isEmpty(this.finalUrl)) {
                    logger.warning("Failed to find final downloadurl");
                    /* 2020-05-05: It is sometimes tricky to determine the exact error */
                    final String response_content_type = br.getHttpConnection().getContentType();
                    if (response_content_type != null && response_content_type.contains("application/json")) {
                        logger.info("Browser contains json response --> Probably error --> Retrying");
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Access denied or content offline");
                    } else {
                        logger.warning("Unknown error");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                }
                // most if not all components already opened connection via either linkOk or photolinkOk
                br.getHeaders().put("Accept-Encoding", "identity");
                dl = new jd.plugins.BrowserAdapter().openDownload(br, link, this.finalUrl, isResumeSupported(link, this.finalUrl), getMaxChunks(link, this.finalUrl));
            }
            if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404");
            } else if (dl.getConnection().getResponseCode() == 416) {
                dl.getConnection().disconnect();
                logger.info("Resume failed --> Retrying from zero");
                link.setChunksProgress(null);
                throw new PluginException(LinkStatus.ERROR_RETRY);
            } else if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                logger.info("vk.com: Plugin broken after download-try");
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl.startDownload();
        }
    }

    private String decryptURLSubL(String decryptType, String t, String e) throws PluginException {
        final String result;
        if (decryptType == null) {
            result = null;
        } else {
            if ("v".equals(decryptType)) {
                result = new StringBuffer(t).reverse().toString();
            } else if ("r".equals(decryptType)) {
                final int pos = Integer.parseInt(e);
                final StringBuffer sb = new StringBuffer(t);
                String o = ALPHANUMERIC + ALPHANUMERIC;
                for (int a = sb.length() - 1; a >= 0; a--) {
                    int i = o.indexOf(sb.charAt(a));
                    if (i != -1) {
                        i = i - pos;
                        if (i < 0) {
                            i = o.length() + i;
                        }
                        sb.setCharAt(a, o.substring(i, i + 1).charAt(0));
                    }
                }
                result = sb.toString();
            } else if ("s".equals(decryptType)) {
                int eVal = Integer.parseInt(e);
                result = decryptURLSubLS(t, eVal);
            } else if ("i".equals(decryptType)) {
                if (vkID == null) {
                    logger.warning("vkID is null but we need it");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                int eVal = Integer.parseInt(e);
                result = decryptURLSubLS(t, eVal ^ Integer.parseInt(vkID));
            } else if ("x".equals(decryptType)) {
                final char eCharValue = e.charAt(0);
                final StringBuffer sb = new StringBuffer();
                for (int i = 0; i < t.length(); i++) {
                    sb.append(Character.valueOf((char) (t.charAt(i) ^ eCharValue)));
                }
                result = sb.toString();
            } else {
                result = null;
            }
        }
        return result;
    }

    private String decryptURLSubLS(final String t, final int e) {
        if (t.length() > 0) {
            List<Integer> o = decryptURLSubS(t, e);
            StringBuffer result = new StringBuffer(t);
            int i = 1;
            o.remove(0);
            for (int oIndex : o) {
                String tmp = result.substring(oIndex, oIndex + 1);
                result.replace(oIndex, oIndex + 1, result.substring(i, i + 1));
                result.replace(i, i + 1, tmp);
                i++;
            }
            return result.toString();
        } else {
            return null;
        }
    }

    private String decryptURLSubA(String t) {
        if (t == null || t.length() % 4 == 1) {
            return null;
        }
        StringBuffer result = new StringBuffer();
        int e = 0, i, o = 0;
        for (int a = 0; a < t.length(); a++) {
            i = ALPHANUMERIC.indexOf(t.charAt(a));
            if (i != -1) {
                if (o % 4 != 0) {
                    e = 64 * e + i;
                } else {
                    e = i;
                }
                if (o++ % 4 != 0) {
                    result.append(Character.valueOf((char) (255 & e >> (-2 * o & 6))));
                }
            }
        }
        return result.toString();
    }

    private List<Integer> decryptURLSubS(String t, final int e) {
        int i = t.length();
        List<Integer> result = new ArrayList<Integer>();
        if (i > 0) {
            int eVal = e;
            for (int a = i; a > 0; a--) {
                eVal = Math.abs(eVal);
                eVal = (i * a ^ eVal + (a - 1)) % i;
                result.add(eVal);
            }
        }
        return result;
    }

    private String decryptAudioURL(final String url) throws PluginException {
        String result = url;
        if (!url.contains("audio_api_unavailable")) {
            return result;
        }
        String[] hash = url.split("\\?extra=")[1].split("#");
        String o = decryptURLSubA(hash[1]);
        String e = decryptURLSubA(hash[0]);
        if (o == null || e == null) {
            return result;
        }
        String[] oa = o.split(Character.valueOf((char) 9).toString());
        for (int n = oa.length - 1; n >= 0; n--) {
            String[] l = oa[n].split(Character.valueOf((char) 11).toString());
            if (l.length == 0) {
                return result;
            }
            if (!"v".equals(l[0]) && !"r".equals(l[0]) && !"s".equals(l[0]) && !"i".equals(l[0]) && !"x".equals(l[0])) {
                return result;
            }
            if ("v".equals(l[0])) {
                e = decryptURLSubL(l[0], e, null);
            } else {
                e = decryptURLSubL(l[0], e, l[1]);
            }
        }
        if (e != null && e.startsWith("http")) {
            result = e;
        }
        return result;
    }

    private String audioGetDirectURL() throws PluginException {
        String url = this.br.getRegex("\"(http[^<>\"\\']+\\.mp3[^<>\"\\']*?)\"").getMatch(0);
        if (url != null) {
            url = url.replace("\\", "");
            url = decryptAudioURL(url);
            if (!audioIsValidDirecturl(url)) {
                url = null;
            }
        }
        return url;
    }

    /* 2016-01-05: Check for invalid audioURL (e.g. decryption fails)! */
    public static boolean audioIsValidDirecturl(final String url) {
        if (url == null || (url != null && url.matches(".+audio_api_unavailable\\.mp3.*?"))) {
            return false;
        } else {
            return true;
        }
    }

    private static void setHeadersPhoto(final Browser br) {
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        br.getHeaders().put("Origin", "https://vk.com");
    }

    public static void setHeaderRefererPhoto(final Browser br) {
        /* TODO: This is wrong! Use the main URL/Content-URL instead! */
        br.getHeaders().put("Referer", "https://" + DOMAIN + "/al_photos.php");
    }

    public static void accessVideo(final Plugin plugin, final Browser br, final String videoURL, final String oid, final String id, final String listID) throws Exception {
        final String videoids_together = oid + "_" + id;
        if (videoURL.matches(VKontakteRu.PATTERN_VIDEO_SINGLE_Z) && listID == null) {
            /**
             * 2022-01-07: Special: Extra request of original URL in beforehand required to find listID and/or set required cookies!
             */
            br.getPage(videoURL);
            final String thisListID = br.getRegex("\\?z=video" + videoids_together + "%2F([a-f0-9]+)\"").getMatch(0);
            final UrlQuery query = new UrlQuery();
            query.add("act", "show");
            query.add("al", "1");
            query.add("autoplay", "0");
            if (thisListID != null) {
                /* Should always be given! */
                query.add("list", thisListID);
            }
            query.add("module", "");
            query.add("video", videoids_together);
            br.postPage("/al_video.php?act=show", query);
        } else if (listID != null) {
            final UrlQuery query = new UrlQuery();
            query.add("act", "show_inline");
            query.add("al", "1");
            query.add("list", listID);
            query.add("module", "public");
            query.add("video", videoids_together);
            br.postPage(getProtocol() + "vk.com/al_video.php", query);
        } else {
            br.getPage(getProtocol() + "vk.com/video" + videoids_together);
            handleTooManyRequests(plugin, br);
        }
    }

    private static Map<String, String> LOCK_429 = new HashMap<String, String>();

    public static void handleTooManyRequests(final Plugin plugin, final Browser br) throws Exception {
        synchronized (LOCK_429) {
            final boolean isFollowRedirect = br.isFollowingRedirects();
            if (!isFollowRedirect && StringUtils.containsIgnoreCase(br.getRedirectLocation(), "/429.html")) {
                br.followRedirect();
            }
            URL url = br._getURL();
            if (url != null && StringUtils.equals(url.getPath(), "/429.html")) {
                final UrlQuery query = UrlQuery.parse(url.getQuery());
                final String hash429 = query.get("hash429");
                if (hash429 != null) {
                    Thread.sleep(1000);
                    String newLocation = url.toString();
                    if (StringUtils.containsIgnoreCase(newLocation, "&key=")) {
                        // remove existing key from query
                        newLocation = newLocation.replaceFirst("(?i)(&key=[^&#]+)", "");
                    }
                    newLocation = newLocation + "&key=" + Hash.getMD5(hash429);
                    br.getPage(newLocation);
                    if (!isFollowRedirect) {
                        br.followRedirect();
                    }
                    if (!StringUtils.equals(br._getURL().getPath(), "/429.html") && !StringUtils.containsIgnoreCase(br.getRedirectLocation(), "/429.html")) {
                        final String solution429 = br.getHostCookie("solution429");
                        if (solution429 != null) {
                            LOCK_429.put("hash", hash429);
                            LOCK_429.put("solution", solution429);
                        }
                        return;
                    }
                }
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(br, account, true);
        ai.setUnlimitedTraffic();
        ai.setStatus("Free Account");
        account.setType(AccountType.FREE);
        return ai;
    }

    private void generalErrorhandling(final Browser br) throws PluginException {
        if (br.containsHTML(VKontakteRuHoster.TEMPORARILYBLOCKED)) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Too many requests in a short time", 60 * 1000l);
        } else if (br.containsHTML("You have to log in to view this user")) {
            /*
             * 2019-08-06 e.g.
             * "CENSORED<!><!>3<!>4231<!>8<!>You have to log in to view this user&#39;s photos.<!><!>CENSORED<!><!pageview_candidate>"
             */
            throw new AccountRequiredException();
        }
    }

    @Override
    public String getAGBLink() {
        return getBaseURL() + "/help.php?page=terms";
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

    /**
     * JD2 CODE. DO NOT USE OVERRIDE FOR JD=) COMPATIBILITY REASONS!
     */
    public boolean isProxyRotationEnabledForLinkChecker() {
        return false;
    }

    @Override
    public boolean isResumeable(DownloadLink link, Account account) {
        return true;
    }

    private boolean isDRMProtected(final DownloadLink link) {
        if (link.getPluginPatternMatcher().matches(TYPE_AUDIOLINK)) {
            /* 2022-01-19: Handling for such links is broken AND they're streamed in DRM protected (well, only AES128) HLS. */
            return true;
        } else {
            return false;
        }
    }

    private boolean isHLS(final DownloadLink link, final String url) {
        final String qualityStr = link.getStringProperty(PROPERTY_VIDEO_SELECTED_QUALITY);
        if (StringUtils.equalsIgnoreCase(qualityStr, "HLS")) {
            return true;
        } else if (url == null) {
            return false;
        } else if (url.contains(".m3u8") || url.contains("video_hls.php")) {
            return true;
        } else {
            return false;
        }
    }

    /**
     *
     * @return <b>1</b>: Link is valid and can be downloaded, <b>0</b>: Link leads to HTML, times out or other problems occured, <b>404</b>:
     *         Server 404 response
     */
    private int linkOk(final DownloadLink link, final boolean isDownload) throws Exception {
        if (StringUtils.isEmpty(finalUrl)) {
            return 0;
        }
        final Browser br2 = this.br.cloneBrowser();
        br2.setFollowRedirects(true);
        br2.getHeaders().put("Accept-Encoding", "identity");
        final PluginForHost orginalPlugin = link.getLivePlugin();
        if (!isDownload) {
            link.setLivePlugin(this);
        }
        URLConnectionAdapter con = null;
        boolean closeConnection = true;
        try {
            if (isDownload && !isHLS(link, finalUrl)) {
                dl = new jd.plugins.BrowserAdapter().openDownload(br2, link, finalUrl, isResumeSupported(link, finalUrl), getMaxChunks(link, finalUrl));
                con = dl.getConnection();
            } else {
                con = br2.openGetConnection(finalUrl);
            }
            if (this.looksLikeDownloadableContent(con)) {
                if (!isHLS(link, finalUrl)) {
                    final long foundFilesize = con.getCompleteContentLength();
                    final String headerFilename = Plugin.getFileNameFromHeader(con);
                    if (link.getFinalFileName() == null && headerFilename != null) {
                        link.setFinalFileName(Encoding.htmlDecode(headerFilename));
                    }
                    /* 2016-12-01: Set filesize if it has not been set before. */
                    if (link.getDownloadSize() < foundFilesize) {
                        link.setDownloadSize(foundFilesize);
                    }
                }
                if (isDownload) {
                    closeConnection = false;
                }
                return 1;
            } else {
                // request range fucked
                if (con.getResponseCode() == 416) {
                    logger.info("Resume failed --> Retrying from zero");
                    link.setChunksProgress(null);
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }
                if (con.getResponseCode() == 404) {
                    return 404;
                }
                return 0;
            }
        } catch (final BrowserException ebr) {
            /* This happens e.g. for temporarily unavailable videos. */
            throw ebr;
        } catch (final Exception e) {
            return 0;
        } finally {
            if (closeConnection) {
                try {
                    if (con != null) {
                        con.disconnect();
                    }
                } catch (final Throwable t) {
                }
                link.setLivePlugin(orginalPlugin);
            }
        }
    }

    private int getMaxChunks(final DownloadLink link, final String url) {
        if (this.isTypeVideo(link.getPluginPatternMatcher())) {
            return 0;
        } else {
            return 1;
        }
    }

    /**
     *
     * @return <b>true</b>: Link is valid and can be downloaded <b>false</b>: Link leads to HTML, times out or other problems occured - link
     *         is not downloadable!
     */
    private boolean photolinkOk(final DownloadLink link, String finalfilename, final boolean isDownload, String directurl) throws Exception {
        if (StringUtils.isEmpty(directurl)) {
            return false;
        }
        final Browser br2 = this.br.cloneBrowser();
        /* Correct final URLs according to users' plugin settings. */
        directurl = photo_correctLink(directurl);
        /* Ignore invalid urls. Usually if we have such an url the picture is serverside temporarily unavailable. */
        if (directurl.contains("_null_")) {
            return false;
        }
        br2.getHeaders().put("Accept-Encoding", "identity");
        final PluginForHost orginalPlugin = link.getLivePlugin();
        if (!isDownload) {
            link.setLivePlugin(this);
        }
        URLConnectionAdapter con = null;
        boolean closeConnection = true;
        try {
            if (isDownload) {
                dl = new jd.plugins.BrowserAdapter().openDownload(br2, link, directurl, isResumeSupported(link, directurl), getMaxChunks(link, directurl));
                con = dl.getConnection();
            } else {
                con = br2.openGetConnection(directurl);
            }
            // request range fucked
            if (con.getResponseCode() == 416) {
                logger.info("Resume failed --> Retrying from zero");
                link.setChunksProgress(null);
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            if (con.getCompleteContentLength() <= 100 || con.getResponseCode() == 404 || con.getResponseCode() == 502) {
                /* Photo is supposed to be online but it's not downloadable */
                return false;
            }
            if (!this.looksLikeDownloadableContent(con)) {
                return false;
            }
            finalfilename = photoGetFinalFilename(this.getPhotoID(link), finalfilename, directurl);
            if (finalfilename == null) {
                /* This should actually never happen. */
                finalfilename = Encoding.htmlDecode(getFileNameFromHeader(con));
            }
            link.setFinalFileName(finalfilename);
            link.setProperty(PROPERTY_PHOTOS_picturedirectlink, directurl);
            if (isDownload) {
                closeConnection = false;
            }
            return true;
        } catch (final BrowserException ebr) {
            logger.info("BrowserException on directlink: " + directurl);
            logger.log(ebr);
            if (isDownload) {
                throw ebr;
            }
            return false;
        } catch (final ConnectException ec) {
            logger.info("Directlink timed out: " + directurl);
            logger.log(ec);
            if (isDownload) {
                throw ec;
            }
            return false;
        } catch (final PluginException p) {
            // required for exists on disk and set mirror as complete.
            throw p;
        } catch (final SkipReasonException s) {
            // required for file exists on disk (standard).
            throw s;
        } catch (final Exception e) {
            logger.log(e);
            if (isDownload) {
                throw e;
            }
            return false;
        } finally {
            if (closeConnection) {
                try {
                    if (con != null) {
                        con.disconnect();
                    }
                } catch (final Throwable t) {
                }
                link.setLivePlugin(orginalPlugin);
            }
        }
    }

    private boolean isResumeSupported(final DownloadLink link, final String url) throws IOException {
        if (this.isTypeVideo(link.getPluginPatternMatcher())) {
            return true;
        } else if (url != null && (url.matches(".+\\.(mp4)$") || new URL(url).getFile().matches(".+\\.(mp4)$"))) {
            return true;
        } else if (url != null && (url.matches(".+\\.(mp3|aac|m4a)$") || new URL(url).getFile().matches(".+\\.(mp3|aac|m4a)$"))) {
            return true;
        } else if (url != null && (url.matches(".+\\.(jpe?g|png|gif|bmp)$") || new URL(url).getFile().matches(".+\\.(jpe?g|png|gif|bmp)$"))) {
            return false;
        } else {
            return false;
        }
    }

    /**
     * Returns the final filename for photourls based on given circumstances and user-setting
     * VKPHOTOS_TEMP_SERVER_FILENAME_AS_FINAL_FILENAME .
     */
    public static String photoGetFinalFilename(final String photo_id, String finalfilename, final String directlink) throws MalformedURLException {
        final String url_filename = directlink != null ? getFileNameFromURL(new URL(directlink)) : null;
        final PluginForHost plg = JDUtilities.getPluginForHost(DOMAIN);
        if (finalfilename != null) {
            /* Do nothing - final filename has already been set (usually this is NOT the case). */
        } else if (plg != null && plg.getPluginConfig().getBooleanProperty(VKPHOTOS_TEMP_SERVER_FILENAME_AS_FINAL_FILENAME, default_VKPHOTOS_TEMP_SERVER_FILENAME_AS_FINAL_FILENAME) && !StringUtils.isEmpty(url_filename)) {
            finalfilename = url_filename;
        } else if (plg != null && plg.getPluginConfig().getBooleanProperty(VKPHOTOS_TEMP_SERVER_FILENAME_AND_OWNER_ID_AND_CONTENT_ID_AS_FINAL_FILENAME, default_VKPHOTOS_TEMP_SERVER_FILENAME_AND_OWNER_ID_AND_CONTENT_ID_AS_FINAL_FILENAME) && !StringUtils.isEmpty(url_filename)) {
            finalfilename = photo_id + " - " + url_filename;
        } else if (directlink != null) {
            /* Default filename */
            finalfilename = photo_id + getFileNameExtensionFromString(directlink, ".jpg");
        } else {
            /* Default filename */
            finalfilename = photo_id + ".jpg";
        }
        return finalfilename;
    }

    /** TODO: Maybe add login via API: https://vk.com/dev/auth_mobile */
    public void login(final Browser br, final Account account, final boolean forceCookieCheck) throws Exception {
        synchronized (VKontakteRuHoster.LOCK) {
            br.setCookiesExclusive(true);
            prepBrowser(br, false);
            br.setFollowRedirects(true);
            this.vkID = account.getStringProperty("vkid");
            final Cookies cookies = account.loadCookies("");
            final Cookies userCookies = Cookies.parseCookiesFromJsonString(account.getPass(), getLogger());
            try {
                if (cookies != null) {
                    logger.info("Attempting cookie login");
                    br.setCookies(DOMAIN, cookies);
                    if (System.currentTimeMillis() - account.getCookiesTimeStamp("") <= trust_cookie_age && !forceCookieCheck) {
                        /* We trust these cookies --> Do not check them */
                        logger.info("Trust login cookies as they're not yet that old");
                        return;
                    } else {
                        logger.info("Attempting cookie login");
                        if (checkCookieLogin(br, account)) {
                            return;
                        }
                    }
                }
                if (userCookies != null) {
                    logger.info("Attempting user cookie login");
                    br.setCookies(DOMAIN, userCookies);
                    if (checkCookieLogin(br, account)) {
                        return;
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "Cookie login failed", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                logger.info("Performing full login");
                br.getPage(getBaseURL() + "/");
                handleTooManyRequests(this, br);
                final Form login = br.getFormbyProperty("id", "quick_login_form");
                if (login == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                login.put("email", Encoding.urlEncode(account.getUser()));
                login.put("pass", Encoding.urlEncode(account.getPass()));
                br.submitForm(login);
                // should redirect to /login/act=slogin....
                br.getPage("/");
                // language set in user profile, so after login it could be changed! We don't want this, we need to save and use ENGLISH
                if (!"3".equals(br.getCookie(DOMAIN, "remixlang"))) {
                    br.setCookie(DOMAIN, "remixlang", "3");
                    br.getPage(br.getURL());
                }
                /* Do NOT check based on cookies as they sometimes change them! */
                if (!isLoggedinHTML(br)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                /* Finish login if needed */
                final Form finalLoginStep = br.getFormbyProperty("name", "login");
                if (finalLoginStep != null) {
                    finalLoginStep.put("email", Encoding.urlEncode(account.getUser()));
                    finalLoginStep.put("pass", Encoding.urlEncode(account.getPass()));
                    finalLoginStep.put("expire", "0");
                    br.submitForm(finalLoginStep);
                }
                this.vkID = regExVKAccountID(br);
                /* Save cookies */
                account.saveCookies(br.getCookies(DOMAIN), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            } finally {
                /* Save accountID as we need it later to decrypt downloadurls. */
                if (this.vkID != null) {
                    account.setProperty("vkid", this.vkID);
                }
            }
        }
    }

    private boolean checkCookieLogin(final Browser br, final Account account) throws Exception {
        br.getPage(getBaseURL());
        handleTooManyRequests(this, br);
        // non language, check
        if (isLoggedinHTML(br)) {
            logger.info("Cookie login successful");
            // language set in user profile, so after 'login' OR 'login check' it could be changed!
            if (!"3".equals(br.getCookie(DOMAIN, "remixlang"))) {
                br.setCookie(DOMAIN, "remixlang", "3");
            }
            this.vkID = regExVKAccountID(br);
            /* Refresh timestamp */
            account.saveCookies(br.getCookies(DOMAIN), "");
            return true;
        } else {
            /* Delete cookies / Headers to perform a full login */
            logger.info("Cookie login failed");
            br.clearAll();
            prepBrowser(br, false);
            return false;
        }
    }

    private static boolean isLoggedinHTML(final Browser br) {
        return br.containsHTML("id=\"logout_link_td\"|id=\"(?:top_)?logout_link\"");
    }

    private String regExVKAccountID(final Browser br) {
        return br.getRegex("\\(\\{\"id\":(\\d+),").getMatch(0);
    }

    /* Handle all kinds of stuff that disturbs the downloadflow */
    private void getPageSafe(final Account acc, final DownloadLink link, final String page) throws Exception {
        getPageSafe(br, acc, link, page);
    }

    private void getPageSafe(final Browser br, final Account acc, final DownloadLink link, final String page) throws Exception {
        br.getPage(page);
        handleTooManyRequests(this, br);
        if (acc != null && br.getRedirectLocation() != null && br.getRedirectLocation().contains("login.vk.com/?role=fast")) {
            logger.info("Avoiding 'login.vk.com/?role=fast&_origin=' security check by re-logging in...");
            // Force login
            login(br, acc, false);
            br.getPage(page);
        } else if (acc != null && br.toString().length() < 100 && br.toString().trim().matches("\\d+<\\!><\\!>\\d+<\\!>\\d+<\\!>\\d+<\\!>[a-z0-9]+|\\d+<!><\\!>.+/login\\.php\\?act=security_check.+")) {
            logger.info("Avoiding possible outdated cookie/invalid account problem by re-logging in...");
            // Force login
            login(br, acc, false);
            br.getPage(page);
        } else if (br.getRedirectLocation() != null && br.getRedirectLocation().replaceAll("https?://(\\w+\\.)?vk\\.com", "").equals(page.replaceAll("https?://(\\w+\\.)?vk\\.com", ""))) {
            br.getPage(br.getRedirectLocation());
        }
        generalErrorhandling(br);
    }

    private void postPageSafe(final Account acc, final DownloadLink link, final String page, final String postData) throws Exception {
        postPageSafe(this.br, acc, link, page, postData);
    }

    private void postPageSafe(final Browser br, final Account acc, final DownloadLink link, final String page, final String postData) throws Exception {
        br.postPage(page, postData);
        if (acc != null && br.getRedirectLocation() != null && br.getRedirectLocation().contains("login.vk.com/?role=fast")) {
            logger.info("Avoiding 'login.vk.com/?role=fast&_origin=' security check by re-logging in...");
            // Force login
            login(br, acc, false);
            br.postPage(page, postData);
        } else if (acc != null && br.toString().length() < 100 && br.toString().trim().matches("\\d+<\\!><\\!>\\d+<\\!>\\d+<\\!>\\d+<\\!>[a-z0-9]+|\\d+<!><\\!>.+/login\\.php\\?act=security_check.+")) {
            logger.info("Avoiding possible outdated cookie/invalid account problem by re-logging in...");
            // TODO: Change/remove this - should not be needed anymore!
            // Force login
            login(br, acc, false);
            br.postPage(page, postData);
        }
        generalErrorhandling(br);
    }

    public static Browser prepBrowser(final Browser br, final boolean isDecryption) {
        String useragent = SubConfiguration.getConfig("vk.com").getStringProperty(VKADVANCED_USER_AGENT, "");
        if (StringUtils.isEmpty(useragent) || useragent.length() <= 3) {
            useragent = null;
        }
        if (useragent != null) {
            br.getHeaders().put("User-Agent", useragent);
        }
        /* Set English language */
        br.setCookie(DOMAIN, "remixlang", "3");
        if (isDecryption) {
            // this causes epic issues in download tasks not timing out in reasonable time. We should refrain from setting in plugin
            // timeouts unless its _REALLY_ needed! 20160612-raztoki
            br.setReadTimeout(1 * 60 * 1000);
            br.setConnectTimeout(2 * 60 * 1000);
        }
        /* Loads can be very high. Site sometimes returns more than 10 000 entries with 1 request. */
        br.setLoadLimit(br.getLoadLimit() * 4);
        synchronized (LOCK_429) {
            final String hash = LOCK_429.get("hash");
            final String solution = LOCK_429.get("solution");
            if (StringUtils.isAllNotEmpty(hash, solution)) {
                br.setCookie(DOMAIN, "hash429", hash);
                br.setCookie(DOMAIN, "solution429", solution);
            }
        }
        return br;
    }

    @Override
    public void errLog(Throwable e, Browser br, LogSource log, DownloadLink link, Account account) {
        if (e != null && e instanceof PluginException && ((PluginException) e).getLinkStatus() == LinkStatus.ERROR_PLUGIN_DEFECT) {
            final LogSource errlogger = LogController.getInstance().getLogger("PluginErrors");
            try {
                errlogger.severe("-START OF REPORT-");
                errlogger.severe("HosterPlugin out of date: " + this + " :" + getVersion());
                errlogger.severe("URL: " + link.getPluginPatternMatcher() + " | ContentUrl: " + link.getContentUrl() + " | ContainerUrl: " + link.getContainerUrl() + " | OriginUrl: " + link.getOriginUrl() + " | ReferrerUrl: " + link.getReferrerUrl());
                if (e != null) {
                    errlogger.log(e);
                }
                if (br != null && br.getRequest() != null) {
                    errlogger.info("\r\n" + br.getRequest().toString());
                    errlogger.severe("-END OF REPORT-");
                }
            } finally {
                errlogger.close();
            }
        }
    }

    /**
     * Try to get best quality and test links until a working link is found. Will also handle errors in case
     *
     * @throws IOException
     *
     * @param checkDownloadability
     *            true: Return best quality which also can be downloaded <br/>
     *            false: return best quality downloadurl without checking whether it is downloadable or not
     */
    @SuppressWarnings({ "unchecked" })
    private String getHighestQualityPictureDownloadurl(final Browser br, final DownloadLink dl, final boolean checkDownloadability) throws Exception {
        String json = picturesGetJsonFromHtml(br);
        if (json == null) {
            json = picturesGetJsonFromXml(br);
        }
        if (json == null) {
            /* 2019-10-02: Fallback - e.g. single image from album will return plain json. */
            json = br.toString();
        }
        if (json == null) {
            if (br.containsHTML("<!>deleted<!>")) {
                // we suffer some desync between website and api. I guess due to website pages been held in cache.
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            logger.warning("Failed to find source json of picturelink");
            return null;
        }
        final String thisid = getPhotoID(dl);
        final Object photoo = findPictureObject(JavaScriptEngineFactory.jsonToJavaObject(json), thisid);
        final Map<String, Object> sourcemap = (Map<String, Object>) photoo;
        if (sourcemap == null) {
            logger.info(json);
            logger.warning("Failed to find specified source json of picturelink:" + thisid);
            return null;
        }
        boolean success = false;
        /* Count how many possible downloadlinks we have */
        int links_count = 0;
        String dllink = null;
        /* 2019-10-02: New: x, r, q, p, o */
        final String[] qs = { "w_", "z_", "y_", "x_", "m_", "x_", "r_", "q_", "p_", "_o" };
        for (final String q : qs) {
            if (this.isAbort()) {
                logger.info("User stopped downloads --> Stepping out of getHighestQualityPic to avoid 'freeze' of the current DownloadLink");
                /* Avoid unnecessary 'plugin defect's in the logs. */
                throw new PluginException(LinkStatus.ERROR_RETRY, "User aborted download");
            }
            final String srcstring = q + "src";
            final Object picobject = sourcemap.get(srcstring);
            /* Check if the link we eventually found is downloadable. */
            if (picobject != null) {
                dllink = (String) picobject;
                if (!dllink.startsWith("http")) {
                    /* Skip invalid objects */
                    continue;
                }
                links_count++;
                if (checkDownloadability) {
                    /* Make sure that url is downloadable */
                    if (photolinkOk(dl, null, "m_".equals(q), dllink)) {
                        return dllink;
                    }
                } else {
                    /* Don't check whether URL is downloadable or not - just return URL to highest quality picture! */
                    return dllink;
                }
            }
        }
        if (links_count == 0) {
            logger.warning("Found no possible downloadlink for current picturelink --> Maybe plugin broken");
            return null;
        } else if (links_count > 0 && !success) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Photo is temporarily unavailable or offline (server issues)", 30 * 60 * 1000l);
        }
        return null;
    }

    /** Recursive function to find object containing picture (download) information. */
    private Object findPictureObject(final Object o, final String picid) {
        if (o instanceof Map) {
            final Map<String, Object> entrymap = (Map<String, Object>) o;
            for (final Map.Entry<String, Object> cookieEntry : entrymap.entrySet()) {
                final String key = cookieEntry.getKey();
                final Object value = cookieEntry.getValue();
                if (key.equals("id") && value instanceof String) {
                    final String entry_id = (String) value;
                    if (entry_id.equals(picid)) {
                        return o;
                    } else {
                        continue;
                    }
                } else if (value instanceof List || value instanceof Map) {
                    final Object pico = findPictureObject(value, picid);
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
                    final Object pico = findPictureObject(arrayo, picid);
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

    /** RegEx source-json from html. */
    private static String picturesGetJsonFromHtml(final Browser br) {
        return br.getRegex("ajax\\.preload\\(\\'al_photos\\.php\\'\\s*?,\\s*?\\{[^\\}]*?\\}\\s*?,\\s*?(\\[.+)").getMatch(0);
    }

    /** RegEx source-json from xml. */
    private String picturesGetJsonFromXml(final Browser br) {
        return br.getRegex("<\\!json>(.*?)<\\!><\\!json>").getMatch(0);
    }

    /**
     * Try to get best quality and test links till a working link is found as it can happen that the found link is offline but others are
     * online. This function is made to check the information which has been saved via decrypter as the property
     * PROPERTY_PHOTOS_directurls_fallback on the DownloadLink.
     *
     * @throws IOException
     * @param checkDownloadability
     *            true: Return best quality which also can be downloaded <br/>
     *            false: return best quality downloadurl without checking whether it is downloadable or not
     */
    private String getHighestQualityPicFromSavedJson(final DownloadLink link, final String picture_preview_json, final boolean checkDownloadability) throws Exception {
        String dllink = getHighestQualityPicFromSavedJson(picture_preview_json);
        if (dllink == null || !dllink.startsWith("http")) {
            return null;
        }
        /* 2019-08-06: Extension is sometimes missing but required! */
        if (!dllink.endsWith(".jpg")) {
            dllink += ".jpg";
        }
        if (checkDownloadability) {
            if (photolinkOk(link, null, true, dllink)) {
                return dllink;
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Photo is temporarily unavailable or offline (server issues)", 30 * 60 * 1000l);
            }
        } else {
            return dllink;
        }
    }

    public static String getHighestQualityPicFromSavedJson(String picture_preview_json) {
        String dllink = null;
        if (Encoding.isHtmlEntityCoded(picture_preview_json)) {
            picture_preview_json = Encoding.htmlDecode(picture_preview_json);
        }
        if (picture_preview_json != null) {
            try {
                Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(picture_preview_json);
                if (entries.containsKey("temp")) {
                    /* 2020-01-28 */
                    entries = (Map<String, Object>) entries.get("temp");
                }
                final String base = (String) entries.get("base");
                final Iterator<Entry<String, Object>> iterator = entries.entrySet().iterator();
                int qualityMax = 0;
                int qualityTemp = 0;
                String dllink_temp = null;
                while (iterator.hasNext()) {
                    final Entry<String, Object> entry = iterator.next();
                    final Object picO = entry.getValue();
                    /* Skip invalid objects */
                    if (!(picO instanceof List)) {
                        continue;
                    }
                    final List<Object> ressourcelist = (List<Object>) picO;
                    qualityTemp = (int) JavaScriptEngineFactory.toLong(ressourcelist.get(1), 0);
                    dllink_temp = (String) ressourcelist.get(0);
                    if (qualityTemp > qualityMax) {
                        qualityMax = qualityTemp;
                        dllink = dllink_temp;
                    }
                }
                if (dllink != null && !dllink.startsWith("http") && !StringUtils.isEmpty(base)) {
                    /* Sometimes base is empty and already contained in dllink! */
                    dllink = base + dllink;
                }
            } catch (final Throwable e) {
            }
        }
        return dllink;
    }

    /**
     * Changes server of picture links if wished by user - if not it will change them back to their "original" format. On error (server does
     * not match expected) it won't touch the current finallink at all! Only use this for photo links!
     */
    private String photo_correctLink(String downloadurl) {
        if (downloadurl == null) {
            return null;
        }
        if (true || this.getPluginConfig().getBooleanProperty(VKPHOTO_CORRECT_FINAL_LINKS, false)) {
            if (downloadurl.matches("https://pp\\.vk\\.me/c\\d+/.+")) {
                logger.info("VKPHOTO_CORRECT_FINAL_LINKS enabled --> final link is already in desired format ::: " + downloadurl);
            } else {
                /*
                 * Correct server to get files that are otherwise inaccessible - note that this can also make the finallinks unusable (e.g.
                 * server returns errorcode 500 instead of the file) but this is a very rare problem.
                 */
                final String was = downloadurl;
                final String oldserver = new Regex(downloadurl, "(https?://cs\\d+\\.vk\\.me/)").getMatch(0);
                final String serv_id = new Regex(downloadurl, "cs(\\d+)\\.vk\\.me/").getMatch(0);
                if (oldserver != null && serv_id != null) {
                    final String newserver = "https://pp.vk.me/c" + serv_id + "/";
                    downloadurl = downloadurl.replace(oldserver, newserver);
                    logger.info("VKPHOTO_CORRECT_FINAL_LINKS enabled --> SUCCEEDED to correct finallink ::: Was = " + was + " Now = " + downloadurl);
                } else {
                    logger.warning("VKPHOTO_CORRECT_FINAL_LINKS enabled --> FAILED to correct finallink ::: " + downloadurl);
                }
            }
            return downloadurl;
        } else {
            // disabled as it fucks up links - raztoki20160612
            if (true) {
                return null;
            }
            logger.info("VKPHOTO_CORRECT_FINAL_LINKS DISABLED --> changing final link back to standard");
            if (downloadurl.matches("http://cs\\d+\\.vk\\.me/v\\d+/.+")) {
                logger.info("final link is already in desired format --> Doing nothing");
            } else {
                /* Correct links to standard format */
                final Regex dataregex = new Regex(downloadurl, "(https?://pp\\.vk\\.me/c)(\\d+)/v(\\d+)/");
                final String serv_id = dataregex.getMatch(1);
                final String oldserver = dataregex.getMatch(0) + serv_id + "/";
                if (oldserver != null && serv_id != null) {
                    final String newserver = "http://cs" + serv_id + ".vk.me/";
                    downloadurl = downloadurl.replace(oldserver, newserver);
                    logger.info("VKPHOTO_CORRECT_FINAL_LINKS disabled --> SUCCEEDED to revert corrected finallink");
                } else {
                    logger.warning("VKPHOTO_CORRECT_FINAL_LINKS disabled --> FAILED to revert corrected finallink");
                }
            }
        }
        return null;
    }

    /** Returns photoID in url-form: oid_id (userID_pictureID). */
    private String getPhotoID(final DownloadLink dl) {
        return new Regex(dl.getPluginPatternMatcher(), "vkontaktedecrypted\\.ru/picturelink/((\\-)?[\\d\\-]+_[\\d\\-]+)").getMatch(0);
    }

    private void setConstants(final DownloadLink dl) {
        this.ownerID = getOwnerID(dl);
        this.contentID = getContentID(dl);
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        if (link.getPluginPatternMatcher() == null) {
            return super.getLinkID(link);
        } else {
            final String ownerID = getOwnerID(link);
            final String contentID = getContentID(link);
            if (this.isTypeVideo(link.getPluginPatternMatcher())) {
                return "vkontakte://" + ownerID + "_" + contentID + "_" + link.getStringProperty(PROPERTY_VIDEO_SELECTED_QUALITY, "noneYet");
            } else {
                if (ownerID != null && contentID != null) {
                    /* Non-video items */
                    return "vkontakte://" + ownerID + "_" + contentID;
                } else {
                    /* Fallback */
                    return super.getLinkID(link);
                }
            }
        }
    }

    /** Returns ArrayList of audio Objects for Playlists/Albums after '/al_audio.php' request. */
    public static List<Object> getAudioDataArray(final Browser br) throws Exception {
        final String json = jd.plugins.decrypter.VKontakteRu.regexJsonInsideHTML(br);
        if (json == null) {
            return null;
        }
        final Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(json);
        final List<Object> ressourcelist = (List<Object>) entries.get("list");
        return ressourcelist;
    }

    private String getOwnerID(final DownloadLink dl) {
        String ownerID = dl.getStringProperty("owner_id", null);
        if (ownerID == null && dl.getPluginPatternMatcher().matches(TYPE_AUDIOLINK)) {
            /* E.g. Single audios which get added via wall single post crawler from inside comments of a post. */
            ownerID = new Regex(dl.getPluginPatternMatcher(), TYPE_AUDIOLINK).getMatch(0);
        } else if (ownerID == null && dl.getPluginPatternMatcher().matches(TYPE_PICTURELINK)) {
            ownerID = new Regex(dl.getPluginPatternMatcher(), TYPE_PICTURELINK).getMatch(0);
        } else if (ownerID == null && dl.getPluginPatternMatcher().matches(TYPE_DOCLINK_1)) {
            ownerID = new Regex(dl.getPluginPatternMatcher(), TYPE_DOCLINK_1).getMatch(0);
        } else if (ownerID == null && dl.getPluginPatternMatcher().matches(TYPE_VIDEOLINK)) {
            ownerID = new Regex(dl.getPluginPatternMatcher(), TYPE_VIDEOLINK).getMatch(0);
        } else if (ownerID == null && dl.getPluginPatternMatcher().matches(TYPE_VIDEOLINK_LEGACY)) {
            ownerID = new Regex(dl.getContentUrl(), TYPE_VIDEOLINK).getMatch(0);
            if (ownerID == null) {
                ownerID = dl.getStringProperty("userid", null);
            }
        }
        return ownerID;
    }

    private String getContentID(final DownloadLink dl) {
        String contentID = dl.getStringProperty("content_id", null);
        if (contentID == null && dl.getPluginPatternMatcher().matches(TYPE_AUDIOLINK)) {
            /* E.g. Single audios which get added via wall single post crawler from inside comments of a post. */
            contentID = new Regex(dl.getPluginPatternMatcher(), TYPE_AUDIOLINK).getMatch(1);
        } else if (contentID == null && dl.getPluginPatternMatcher().matches(TYPE_PICTURELINK)) {
            contentID = new Regex(dl.getPluginPatternMatcher(), TYPE_PICTURELINK).getMatch(1);
        } else if (contentID == null && dl.getPluginPatternMatcher().matches(TYPE_DOCLINK_1)) {
            contentID = new Regex(dl.getPluginPatternMatcher(), TYPE_DOCLINK_1).getMatch(1);
        } else if (contentID == null && dl.getPluginPatternMatcher().matches(TYPE_VIDEOLINK)) {
            contentID = new Regex(dl.getPluginPatternMatcher(), TYPE_VIDEOLINK).getMatch(1);
        } else if (contentID == null && dl.getPluginPatternMatcher().matches(TYPE_VIDEOLINK_LEGACY)) {
            contentID = new Regex(dl.getContentUrl(), TYPE_VIDEOLINK).getMatch(1);
            if (contentID == null) {
                contentID = dl.getStringProperty("videoid", null);
            }
        }
        return contentID;
    }

    private boolean isTypeVideo(final String url) {
        if (url == null) {
            return false;
        } else if (url.matches(TYPE_VIDEOLINK)) {
            return true;
        } else if (url.matches(TYPE_VIDEOLINK_LEGACY)) {
            return true;
        } else {
            return false;
        }
    }

    public static String getProtocol() {
        return "https://";
    }

    public static String getBaseURL() {
        return getProtocol() + DOMAIN;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

    @Override
    public String getDescription() {
        return "JDownloader's Vk Plugin helps downloading all sorts of media from vk.com.";
    }

    public static final String   SLEEP_PAGINATION_GENERAL                                                            = "SLEEP_PAGINATION_GENERAL";
    public static final String   SLEEP_TOO_MANY_REQUESTS                                                             = "SLEEP_TOO_MANY_REQUESTS_V1";
    /* Default values... */
    private static final boolean default_fastlinkcheck_FASTLINKCHECK_VIDEO                                           = true;
    public static final boolean  default_FASTCRAWL_VIDEO                                                             = true;
    private static final boolean default_fastlinkcheck_FASTPICTURELINKCHECK                                          = true;
    private static final boolean default_fastlinkcheck_FASTAUDIOLINKCHECK                                            = true;
    public static final int      default_VIDEO_QUALITY_SELECTION_MODE                                                = 0;
    public static final int      default_PREFERRED_VIDEO_QUALITY                                                     = 0;
    public static final boolean  default_ALLOW_BEST                                                                  = false;
    public static final boolean  default_VIDEO_ADD_NAME_OF_UPLOADER_TO_FILENAME                                      = true;
    public static final boolean  default_ALLOW_BEST_OF_SELECTION                                                     = false;
    private static final boolean default_WALL_ALLOW_albums                                                           = true;
    private static final boolean default_WALL_ALLOW_photo                                                            = true;
    private static final boolean default_WALL_ALLOW_audio                                                            = true;
    private static final boolean default_WALL_ALLOW_video                                                            = true;
    private static final boolean default_WALL_ALLOW_urls                                                             = false;
    private static final boolean default_WALL_ALLOW_documents                                                        = true;
    public static final boolean  default_WALL_ALLOW_lookforurlsinsidewallposts                                       = false;
    public static final boolean  default_VKWALL_GRAB_COMMENTS_PHOTOS                                                 = false;
    public static final boolean  default_VKWALL_GRAB_COMMENTS_AUDIO                                                  = false;
    public static final boolean  default_VKWALL_GRAB_COMMENTS_VIDEO                                                  = false;
    public static final boolean  default_VKWALL_GRAB_COMMENTS_URLS                                                   = false;
    public static final String   default_VKWALL_GRAB_URLS_INSIDE_POSTS_REGEX                                         = ".+";
    public static final boolean  default_VKVIDEO_ALBUM_USEIDASPACKAGENAME                                            = false;
    public static final boolean  default_VKVIDEO_USEIDASPACKAGENAME                                                  = false;
    private static final boolean default_VKAUDIO_USEIDASPACKAGENAME                                                  = false;
    private static final boolean default_VKDOCS_USEIDASPACKAGENAME                                                   = false;
    private static final boolean default_VKDOCS_ADD_UNIQUE_ID                                                        = false;
    private static final boolean default_VKPHOTOS_TEMP_SERVER_FILENAME_AS_FINAL_FILENAME                             = false;
    private static final boolean default_VKPHOTOS_TEMP_SERVER_FILENAME_AND_OWNER_ID_AND_CONTENT_ID_AS_FINAL_FILENAME = false;
    private static final boolean default_VKPHOTO_CORRECT_FINAL_LINKS                                                 = false;
    public static final boolean  default_VKWALL_USE_API                                                              = false;
    public static final boolean  default_VKWALL_STORE_PICTURE_DIRECTURLS                                             = false;
    public static final boolean  default_VKWALL_STORE_PICTURE_DIRECTURLS_PREFER_STORED_DIRECTURLS                    = false;
    public static final String   default_user_agent                                                                  = UserAgents.stringUserAgent(BrowserName.Firefox);
    public static final long     defaultSLEEP_PAGINATION_GENERAL                                                     = 1000;
    public static final long     defaultSLEEP_TOO_MANY_REQUESTS                                                      = 3000;

    public static enum QualitySelectionMode implements LabelInterface {
        BEST {
            @Override
            public String getLabel() {
                return "Best quality (ignores quality setting below)";
            }
        },
        BEST_OF_SELECTED {
            @Override
            public String getLabel() {
                return "Best quality (use selected as highest)";
            }
        },
        SELECTED_ONLY {
            @Override
            public String getLabel() {
                return "Selected quality only";
            }
        },
        ALL {
            @Override
            public String getLabel() {
                return "All available qualities (this will disable video album fast crawl!)";
            }
        };
    }

    private String[] getVideoQualitySelectionModeStrings() {
        final QualitySelectionMode[] qualitySelectionModes = QualitySelectionMode.values();
        final String[] ret = new String[qualitySelectionModes.length];
        for (int i = 0; i < qualitySelectionModes.length; i++) {
            ret[i] = qualitySelectionModes[i].getLabel();
        }
        return ret;
    }

    public static enum Quality implements LabelInterface {
        Q2160 {
            @Override
            public String getLabel() {
                return "2160p";
            }
        },
        Q1440 {
            @Override
            public String getLabel() {
                return "1440p";
            }
        },
        Q1080 {
            @Override
            public String getLabel() {
                return "1080p";
            }
        },
        Q720 {
            @Override
            public String getLabel() {
                return "720p";
            }
        },
        Q480 {
            @Override
            public String getLabel() {
                return "480p";
            }
        },
        Q360 {
            @Override
            public String getLabel() {
                return "360p";
            }
        },
        Q240 {
            @Override
            public String getLabel() {
                return "240p";
            }
        },
        Q144 {
            @Override
            public String getLabel() {
                return "144p";
            }
        };
    }

    private String[] getVideoQualityStrings() {
        final Quality[] qualitySelectionModes = Quality.values();
        final String[] ret = new String[qualitySelectionModes.length];
        for (int i = 0; i < qualitySelectionModes.length; i++) {
            ret[i] = qualitySelectionModes[i].getLabel();
        }
        return ret;
    }

    public static QualitySelectionMode getSelectedVideoQualitySelectionMode() {
        final int index = SubConfiguration.getConfig("vk.com").getIntegerProperty(VIDEO_QUALITY_SELECTION_MODE, default_VIDEO_QUALITY_SELECTION_MODE);
        return QualitySelectionMode.values()[Math.min(QualitySelectionMode.values().length - 1, index)];
    }

    public static String getPreferredQualityString() {
        final int index = SubConfiguration.getConfig("vk.com").getIntegerProperty(PREFERRED_VIDEO_QUALITY, default_PREFERRED_VIDEO_QUALITY);
        final Quality quality = Quality.values()[Math.min(Quality.values().length - 1, index)];
        switch (quality) {
        case Q2160:
            return "2160p";
        case Q1440:
            return "1440p";
        case Q1080:
            return "1080p";
        case Q720:
            return "720p";
        case Q480:
            return "480p";
        case Q360:
            return "360p";
        case Q240:
            return "240p";
        case Q144:
            return "144p";
        default:
            /* This should never happen */
            return null;
        }
    }

    public void setConfigElements() {
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Linkcheck settings:"));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), FASTLINKCHECK_VIDEO, "Fast linkcheck for video links (filesize won't be shown in linkgrabber)?").setDefaultValue(default_fastlinkcheck_FASTLINKCHECK_VIDEO));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), FASTCRAWL_VIDEO, "Enable fast video album crawling? Filenames may change before download and filesize won't be visible until download is started.").setDefaultValue(default_FASTCRAWL_VIDEO));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), FASTLINKCHECK_PICTURES, "Fast linkcheck for all picture links (when true or false filename & filesize wont be shown until download starts, when false only task performed is to check if picture has been deleted!)?").setDefaultValue(default_fastlinkcheck_FASTPICTURELINKCHECK));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), FASTLINKCHECK_AUDIO, "Fast linkcheck for audio links (filesize won't be shown in linkgrabber)?").setDefaultValue(default_fastlinkcheck_FASTAUDIOLINKCHECK));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Video settings: (for vk.com/video-XXX_XXX and vk.com/clip-XXX_XXX)"));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_COMBOBOX_INDEX, getPluginConfig(), VIDEO_QUALITY_SELECTION_MODE, getVideoQualitySelectionModeStrings(), "Video quality selection mode").setDefaultValue(default_VIDEO_QUALITY_SELECTION_MODE));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_COMBOBOX_INDEX, getPluginConfig(), PREFERRED_VIDEO_QUALITY, getVideoQualityStrings(), "Preferred video quality").setDefaultValue(default_PREFERRED_VIDEO_QUALITY));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VIDEO_ADD_NAME_OF_UPLOADER_TO_FILENAME, "Append the uploaders' name to the beginning of filenames?").setDefaultValue(default_VIDEO_ADD_NAME_OF_UPLOADER_TO_FILENAME));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Wall settings (for 'vk.com/wall-123...' and 'vk.com/wall-123..._123...' links):"));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_ALBUMS, "Grab album links ('vk.com/album')?").setDefaultValue(default_WALL_ALLOW_albums));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_PHOTOS, JDL.L("plugins.hoster.vkontakteruhoster.wallcheckphotos", "Grab photo links ('vk.com/photo')?")).setDefaultValue(default_WALL_ALLOW_photo));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_AUDIO, JDL.L("plugins.hoster.vkontakteruhoster.wallcheckaudio", "Grab audio links (.mp3 directlinks)?")).setDefaultValue(default_WALL_ALLOW_audio));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_VIDEO, JDL.L("plugins.hoster.vkontakteruhoster.wallcheckvideo", "Grab video links ('vk.com/video')?")).setDefaultValue(default_WALL_ALLOW_video));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_DOCS, JDL.L("plugins.hoster.vkontakteruhoster.wallcheckdocs", "Grab documents?")).setDefaultValue(default_WALL_ALLOW_documents));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_URLS, JDL.L("plugins.hoster.vkontakteruhoster.wallchecklink", "Grab other urls?")).setDefaultValue(default_WALL_ALLOW_urls));
        final ConfigEntry cfg_graburlsinsideposts = new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_URLS_INSIDE_POSTS, JDL.L("plugins.hoster.vkontakteruhoster.wallcheck_look_for_urls_inside_posts", "Grab URLs inside wall posts?")).setDefaultValue(default_WALL_ALLOW_lookforurlsinsidewallposts);
        this.getConfig().addEntry(cfg_graburlsinsideposts);
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_TEXTFIELD, getPluginConfig(), VKWALL_GRAB_URLS_INSIDE_POSTS_REGEX, JDL.L("plugins.hoster.vkontakteruhoster.regExForUrlsInsideWallPosts", "RegEx for URLs from inside wall posts (black-/whitelist): ")).setDefaultValue(default_VKWALL_GRAB_URLS_INSIDE_POSTS_REGEX).setEnabledCondidtion(cfg_graburlsinsideposts, true));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Settings for comments inside 'vk.com/wall-123_123...' links:\r\nIf you enable any of the following checkboxes, all wall comments URLs will be crawled first and then their content.\r\nThis can take a lot of time."));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_COMMENTS_PHOTOS, "Grab photo urls inside comments below single wall posts?").setDefaultValue(default_VKWALL_GRAB_COMMENTS_PHOTOS));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_COMMENTS_AUDIO, "Grab audio urls inside comments below single wall posts?").setDefaultValue(default_VKWALL_GRAB_COMMENTS_AUDIO));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_COMMENTS_VIDEO, "Grab video urls inside comments below single wall posts?").setDefaultValue(default_VKWALL_GRAB_COMMENTS_VIDEO));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_GRAB_COMMENTS_URLS, "Grab other urls inside comments below single wall posts?").setDefaultValue(default_VKWALL_GRAB_COMMENTS_URLS));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Settings for video albums ('vk.com/videosXXX_XXX' links):"));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKVIDEO_ALBUM_USEIDASPACKAGENAME, "Use ownerID as packagename?").setDefaultValue(default_VKVIDEO_ALBUM_USEIDASPACKAGENAME));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Settings for 'vk.com/audios' links:"));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKAUDIOS_USEIDASPACKAGENAME, JDL.L("plugins.hoster.vkontakteruhoster.audiosUseIdAsPackagename", "Use audio-Owner-ID as packagename ('audiosXXXX' or 'audios-XXXX')?")).setDefaultValue(default_VKAUDIO_USEIDASPACKAGENAME));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Settings for 'vk.com/docs' links:"));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKDOCS_USEIDASPACKAGENAME, "Use doc-Owner-ID as packagename ('docsXXXX' or 'docs-XXXX')?").setDefaultValue(default_VKDOCS_USEIDASPACKAGENAME));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKDOCS_ADD_UNIQUE_ID, "Add doc-Owner-ID and doc-Content-ID as a unique identifier to the beginning of filenames?").setDefaultValue(default_VKDOCS_ADD_UNIQUE_ID));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Settings for 'vk.com/photo' links:"));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKPHOTOS_TEMP_SERVER_FILENAME_AS_FINAL_FILENAME, "Use (temporary) server filename as final filename instead of e.g. 'oid_id.jpg'?\r\nNew filenames will look like this: '<server_filename>.jpg'").setDefaultValue(default_VKPHOTOS_TEMP_SERVER_FILENAME_AS_FINAL_FILENAME));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKPHOTOS_TEMP_SERVER_FILENAME_AND_OWNER_ID_AND_CONTENT_ID_AS_FINAL_FILENAME, "Use oid_id AND (temporary) server filename as final filename instead of e.g. 'oid_id.jpg'?\r\nNew filenames will look like this: 'oid_id - <server_filename>.jpg'").setDefaultValue(default_VKPHOTOS_TEMP_SERVER_FILENAME_AND_OWNER_ID_AND_CONTENT_ID_AS_FINAL_FILENAME));
        final ConfigEntry cfg_store_directurls = new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_STORE_PICTURE_DIRECTURLS, "Store picture-directlinks?\r\nThis helps to download images which can only be viewed inside comments but not separately.\r\nThis may also speedup the download process.\r\n WARNING: This may use a lot of RAM if you add big amounts of URLs!").setDefaultValue(default_VKWALL_STORE_PICTURE_DIRECTURLS);
        this.getConfig().addEntry(cfg_store_directurls);
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKWALL_STORE_PICTURE_DIRECTURLS_PREFER_STORED_DIRECTURLS, "Prefer usage of saved crawler picture-directlinks --> This can really speed-up download process if you have many items").setDefaultValue(default_VKWALL_STORE_PICTURE_DIRECTURLS_PREFER_STORED_DIRECTURLS).setEnabledCondidtion(cfg_store_directurls, true));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Advanced settings:\r\n<html><p style=\"color:#F62817\">WARNING: Only change these settings if you really know what you're doing!</p></html>"));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), VKontakteRuHoster.VKPHOTO_CORRECT_FINAL_LINKS, JDL.L("plugins.hoster.vkontakteruhoster.correctFinallinks", "For 'vk.com/photo' links: Change final downloadlinks from 'https?://csXXX.vk.me/vXXX/...' to 'https://pp.vk.me/cXXX/vXXX/...' (forces HTTPS)?")).setDefaultValue(default_VKPHOTO_CORRECT_FINAL_LINKS));
        /* 2019-08-06: Disabled API setting for now as API requires authorization which we do not (yet) support! */
        // this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(),
        // VKontakteRuHoster.VKWALL_USE_API, "For 'vk.com/wall' links: Use API?").setDefaultValue(default_VKWALL_USE_API));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_TEXTFIELD, getPluginConfig(), VKADVANCED_USER_AGENT, "User-Agent: ").setDefaultValue(default_user_agent));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SPINNER, getPluginConfig(), VKontakteRuHoster.SLEEP_PAGINATION_GENERAL, JDL.L("plugins.hoster.vkontakteruhoster.sleep.paginationGeneral", "Define sleep time for general pagination"), 1000, 15000, 500).setDefaultValue(defaultSLEEP_PAGINATION_GENERAL));
        this.getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SPINNER, getPluginConfig(), VKontakteRuHoster.SLEEP_TOO_MANY_REQUESTS, JDL.L("plugins.hoster.vkontakteruhoster.sleep.tooManyRequests", "Define sleep time for 'Temp Blocked' event"), (int) defaultSLEEP_TOO_MANY_REQUESTS, 15000, 500).setDefaultValue(defaultSLEEP_TOO_MANY_REQUESTS));
    }
}