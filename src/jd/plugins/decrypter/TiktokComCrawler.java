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
package jd.plugins.decrypter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.components.config.TiktokConfig;
import org.jdownloader.plugins.components.config.TiktokConfig.CrawlMode;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.controlling.linkcrawler.LinkCrawler;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.hoster.TiktokCom;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { TiktokCom.class })
public class TiktokComCrawler extends PluginForDecrypt {
    public TiktokComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void init() {
        TiktokCom.setRequestLimits();
    }

    public static List<String[]> getPluginDomains() {
        return TiktokCom.getPluginDomains();
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:(?:www|vm)\\.)?" + buildHostsPatternPart(domains) + "/.+");
        }
        return ret.toArray(new String[0]);
    }

    private final String TYPE_REDIRECT      = "https?://vm\\.[^/]+/([A-Za-z0-9]+).*";
    private final String TYPE_USER_USERNAME = "https?://[^/]+/@([^\\?/]+).*";
    private final String TYPE_USER_USER_ID  = "https?://[^/]+/share/user/(\\d+).*";
    /**
     * E.g. https://www.tiktok.com/foryou?is_from_webapp=v1&item_id=12345#/@jewellry2022/video/12345 </br>
     * --> URLs to single video from recommendation
     */
    private final String TYPE_VIDEO         = "https?://[^/]+.*/(@[^/]+/video/\\d+)";

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final PluginForHost plg = this.getNewPluginForHostInstance(this.getHost());
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        if (param.getCryptedUrl().matches(TYPE_REDIRECT)) {
            /* Single redirect URLs */
            br.setFollowRedirects(false);
            br.getPage(param.getCryptedUrl().replaceFirst("http://", "https://"));
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String finallink = br.getRedirectLocation();
            if (finallink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            decryptedLinks.add(createDownloadlink(finallink));
            return decryptedLinks;
        } else if (plg.canHandle(param.getCryptedUrl())) {
            /* Single video URL --> Is handled by host plugin */
            decryptedLinks.add(this.createDownloadlink(param.getCryptedUrl()));
            return decryptedLinks;
        } else if (param.getCryptedUrl().matches(TYPE_VIDEO)) {
            /* Single video in special form --> Form new URL that host plugin can handle */
            final String urlpart = new Regex(param.getCryptedUrl(), TYPE_VIDEO).getMatch(0);
            decryptedLinks.add(this.createDownloadlink("https://" + this.getHost() + "/" + urlpart));
            return decryptedLinks;
        } else if (param.getCryptedUrl().matches(TYPE_USER_USERNAME) || param.getCryptedUrl().matches(TYPE_USER_USER_ID)) {
            return crawlProfile(param);
        } else {
            // unsupported url pattern
            return new ArrayList<DownloadLink>(0);
        }
    }

    public ArrayList<DownloadLink> crawlProfile(final CryptedLink param) throws Exception {
        if (PluginJsonConfig.get(TiktokConfig.class).getProfileCrawlerMaxItemsLimit() == 0) {
            logger.info("User has disabled profile crawler --> Returning empty array");
            return new ArrayList<DownloadLink>();
        }
        if (PluginJsonConfig.get(TiktokConfig.class).getCrawlMode() == CrawlMode.API) {
            return crawlProfileAPI(param);
        } else {
            return crawlProfileWebsite(param);
        }
    }

    /**
     * Use website to crawl all videos of a user. </br>
     * Pagination hasn't been implemented so this will only find the first batch of items - usually around 30 items!
     */
    public ArrayList<DownloadLink> crawlProfileWebsite(final CryptedLink param) throws Exception {
        TiktokCom.prepBRWebsite(br);
        /* Login whenever possible */
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        if (account != null) {
            final PluginForHost plg = this.getNewPluginForHostInstance(this.getHost());
            ((TiktokCom) plg).login(account, false);
        }
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            /* Profile does not exist */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String usernameSlug = new Regex(br.getURL(), TYPE_USER_USERNAME).getMatch(0);
        if (usernameSlug == null) {
            /* Redirect to somewhere else */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (TiktokCom.isBotProtectionActive(this.br)) {
            throw new DecrypterRetryException(RetryReason.CAPTCHA, "Bot protection active, cannot crawl any items of user " + usernameSlug, null, null);
        }
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final TiktokConfig cfg = PluginJsonConfig.get(TiktokConfig.class);
        FilePackage fp = null;
        String username = null;
        try {
            /* First try the "hard" way */
            String json = br.getRegex("window\\['SIGI_STATE'\\]\\s*=\\s*(\\{.*?\\});").getMatch(0);
            if (json == null) {
                json = br.getRegex("<script\\s*id\\s*=\\s*\"SIGI_STATE\"[^>]*>\\s*(\\{.*?\\});?\\s*</script>").getMatch(0);
            }
            final Map<String, Object> entries = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
            final Map<String, Map<String, Object>> itemModule = (Map<String, Map<String, Object>>) entries.get("ItemModule");
            final Map<String, Object> userPost = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "ItemList/user-post");
            final List<Map<String, Object>> preloadList = (List<Map<String, Object>>) userPost.get("preloadList");
            /* Typically we get up to 30 items per page. In some cases we get only 28 or 29 for some reason. */
            final Collection<Map<String, Object>> videos = itemModule.values();
            int index = 0;
            for (final Map<String, Object> video : videos) {
                final Map<String, Object> preloadInfo = preloadList.get(index);
                final Map<String, Object> stats = (Map<String, Object>) video.get("stats");
                final Map<String, Object> streamInfo = (Map<String, Object>) video.get("video");
                final String author = video.get("author").toString();
                final String videoID = (String) video.get("id");
                final String createTimeStr = (String) video.get("createTime");
                final String description = (String) video.get("desc");
                String directurl = (String) streamInfo.get("downloadAddr");
                if (StringUtils.isEmpty(directurl)) {
                    directurl = (String) streamInfo.get("playAddr");
                }
                if (StringUtils.isEmpty(directurl)) {
                    directurl = preloadInfo.get("url").toString();
                }
                if (fp == null) {
                    username = author;
                    fp = this.getFilePackage(username);
                }
                final DownloadLink dl = this.createDownloadlink(getContentURL(author, videoID));
                final String dateFormatted = formatDate(Long.parseLong(createTimeStr));
                dl.setAvailable(true);
                TiktokCom.setDescriptionAndHashtags(dl, description);
                dl.setProperty(TiktokCom.PROPERTY_USERNAME, author);
                dl.setProperty(TiktokCom.PROPERTY_USER_ID, video.get("authorId"));
                dl.setProperty(TiktokCom.PROPERTY_DATE, dateFormatted);
                dl.setProperty(TiktokCom.PROPERTY_VIDEO_ID, videoID);
                TiktokCom.setLikeCount(dl, (Number) stats.get("diggCount"));
                TiktokCom.setPlayCount(dl, (Number) stats.get("playCount"));
                TiktokCom.setShareCount(dl, (Number) stats.get("shareCount"));
                TiktokCom.setCommentCount(dl, (Number) stats.get("commentCount"));
                if (!StringUtils.isEmpty(directurl)) {
                    dl.setProperty(TiktokCom.PROPERTY_DIRECTURL_WEBSITE, directurl);
                }
                TiktokCom.setFilename(dl);
                dl._setFilePackage(fp);
                ret.add(dl);
                distribute(dl);
                if (ret.size() == cfg.getProfileCrawlerMaxItemsLimit()) {
                    logger.info("Stopping because: Reached user defined max items limit: " + cfg.getProfileCrawlerMaxItemsLimit());
                    return ret;
                }
                index++;
            }
            if ((Boolean) userPost.get("hasMore") && cfg.isAddDummyURLProfileCrawlerWebsiteModeMissingPagination()) {
                final DownloadLink dummy = createLinkCrawlerRetry(getCurrentLink(), new DecrypterRetryException(RetryReason.FILE_NOT_FOUND));
                dummy.setFinalFileName("CANNOT_CRAWL_MORE_THAN_" + videos.size() + "_ITEMS_OF_PROFILE_" + usernameSlug);
                dummy.setComment("This crawler plugin cannot handle pagination yet thus it is currently impossible to crawl more than " + videos.size() + " items. Check this forum thread for more info: https://board.jdownloader.org/showthread.php?t=79982");
                if (fp != null) {
                    dummy._setFilePackage(fp);
                }
                distribute(dummy);
                ret.add(dummy);
            }
        } catch (final Throwable e) {
            logger.log(e);
        }
        if (ret.isEmpty()) {
            /* Last chance fallback */
            logger.warning("Fallback to plain html handling");
            final String[] videoIDs = br.getRegex(usernameSlug + "/video/(\\d+)\"").getColumn(0);
            for (final String videoID : videoIDs) {
                final DownloadLink dl = this.createDownloadlink(getContentURL(usernameSlug, videoID));
                TiktokCom.setFilename(dl);
                dl.setAvailable(true);
                if (fp != null) {
                    dl._setFilePackage(fp);
                }
                ret.add(dl);
                if (ret.size() == cfg.getProfileCrawlerMaxItemsLimit()) {
                    logger.info("Stopping because: Reached user defined max items limit: " + cfg.getProfileCrawlerMaxItemsLimit());
                    return ret;
                }
            }
        }
        return ret;
    }

    public ArrayList<DownloadLink> crawlProfileAPI(final CryptedLink param) throws Exception {
        String user_id;
        if (param.getCryptedUrl().matches(TYPE_USER_USER_ID)) {
            /* user_id is given inside URL. */
            user_id = new Regex(param.getCryptedUrl(), TYPE_USER_USER_ID).getMatch(0);
        } else {
            /* Only username is given and we need to find the user_id. */
            final String usernameSlug = new Regex(param.getCryptedUrl(), TYPE_USER_USERNAME).getMatch(0);
            if (usernameSlug == null) {
                /* Developer mistake */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            br.setFollowRedirects(true);
            /* Find userID */
            final Browser website = br.cloneBrowser();
            TiktokCom.prepBRWebAPI(website);
            final UrlQuery query = TiktokCom.getWebsiteQuery();
            query.add("keyword", Encoding.urlEncode(usernameSlug));
            website.getPage("https://www." + this.getHost() + "/api/search/user/preview/?" + query.toString());
            final Map<String, Object> searchResults = JSonStorage.restoreFromString(website.getRequest().getHtmlCode(), TypeRef.HASHMAP);
            final Map<String, Object> user = (Map<String, Object>) JavaScriptEngineFactory.walkJson(searchResults, "sug_list/{0}/extra_info");
            if (user == null) {
                logger.info("Profile doesn't exist or it's a private profile");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String sug_uniq_id = user.get("sug_uniq_id").toString();
            if (!sug_uniq_id.equalsIgnoreCase(usernameSlug)) {
                /* Possibly wrong search result --> Profile we were looking for does not exist */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            /* Now we got the user_id which we need for subsequent API requests. */
            user_id = user.get("sug_user_id").toString();
        }
        TiktokCom.prepBRAPI(this.br);
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final int maxItemsPerPage = 21;
        final UrlQuery query = TiktokCom.getAPIQuery();
        query.add("user_id", user_id);
        query.add("count", Integer.toString(maxItemsPerPage));
        query.add("max_cursor", "0");
        query.add("min_cursor", "0");
        query.add("retry_type", "no_retry");
        query.add("device_id", generateDeviceID());
        int page = 1;
        FilePackage fp = null;
        String author = null;
        final TiktokConfig cfg = PluginJsonConfig.get(TiktokConfig.class);
        do {
            TiktokCom.accessAPI(br, "/aweme/post", query);
            final Map<String, Object> entries = JSonStorage.restoreFromString(br.getRequest().getHtmlCode(), TypeRef.HASHMAP);
            final List<Map<String, Object>> videos = (List<Map<String, Object>>) entries.get("aweme_list");
            if (videos.isEmpty()) {
                if (ret.isEmpty()) {
                    /* User has no video uploads at all. */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else {
                    /* This should never happen! */
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            for (final Map<String, Object> aweme_detail : videos) {
                if (fp == null) {
                    /*
                     * Collect author name on first round because it is not always given before e.g. not given if user adds URL of type
                     * TYPE_USER_USER_ID.
                     */
                    author = JavaScriptEngineFactory.walkJson(aweme_detail, "author/unique_id").toString();
                    fp = getFilePackage(author);
                }
                final DownloadLink link = this.createDownloadlink(getContentURL(author, aweme_detail.get("aweme_id").toString()));
                TiktokCom.parseFileInfoAPI(link, aweme_detail);
                link._setFilePackage(fp);
                ret.add(link);
                distribute(link);
                if (ret.size() == cfg.getProfileCrawlerMaxItemsLimit()) {
                    logger.info("Stopping because: Reached user defined max items limit: " + cfg.getProfileCrawlerMaxItemsLimit());
                    return ret;
                }
            }
            logger.info("Crawled page " + page + " | Found items so far: " + ret.size());
            if (this.isAbort()) {
                break;
            } else if (((Number) entries.get("has_more")).intValue() != 1) {
                logger.info("Stopping because: Reached last page");
                break;
            } else if (videos.size() < maxItemsPerPage) {
                /* Extra fail-safe */
                logger.info("Stopping because: Current page contained less items than " + maxItemsPerPage);
                break;
            }
            query.addAndReplace("max_cursor", entries.get("max_cursor").toString());
            page++;
        } while (true);
        return ret;
    }

    private FilePackage getFilePackage(final String name) {
        final FilePackage fp = FilePackage.getInstance();
        fp.setProperty(LinkCrawler.PACKAGE_CLEANUP_NAME, false);
        fp.setName(name);
        return fp;
    }

    private String getContentURL(final String user, final String videoID) {
        return "https://www." + this.getHost() + "/@" + sanitizeUsername(user) + "/video/" + videoID;
    }

    /** Cleans up given username String. */
    public static String sanitizeUsername(final String user) {
        if (user == null) {
            return null;
        } else if (user.startsWith("@")) {
            return user.substring(1, user.length());
        } else {
            return user;
        }
    }

    /** Returns random 19 digit string. */
    public static String generateDeviceID() {
        return TiktokCom.generateRandomString("1234567890", 19);
    }

    public static String formatDate(final long date) {
        if (date <= 0) {
            return null;
        }
        String formattedDate = null;
        final String targetFormat = "yyyy-MM-dd";
        Date theDate = new Date(date * 1000);
        try {
            final SimpleDateFormat formatter = new SimpleDateFormat(targetFormat);
            formattedDate = formatter.format(theDate);
        } catch (Exception e) {
            /* prevent input error killing plugin */
            formattedDate = Long.toString(date);
        }
        return formattedDate;
    }
}
