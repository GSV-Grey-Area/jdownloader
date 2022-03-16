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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.Time;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.controlling.linkcrawler.LinkCrawler;
import jd.http.Browser;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.HTMLParser;
import jd.plugins.Account;
import jd.plugins.AccountRequiredException;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.hoster.TwitterCom.TwitterConfigInterface;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class TwitterCom extends PornEmbedParser {
    public TwitterCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String                  TYPE_CARD                                                        = "https?://[^/]+/i/cards/tfw/v1/(\\d+)";
    private static final String                  TYPE_USER_ALL                                                    = "https?://[^/]+/([A-Za-z0-9_\\-]+)(?:/(?:media|likes))?(\\?.*)?";
    private static final String                  TYPE_USER_POST                                                   = "https?://[^/]+/([^/]+)/status/(\\d+).*?";
    // private ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
    private static AtomicReference<String>       GUEST_TOKEN                                                      = new AtomicReference<String>();
    private static AtomicLong                    GUEST_TOKEN_TS                                                   = new AtomicLong(-1);
    public static final String                   PROPERTY_USERNAME                                                = "username";
    private static final String                  PROPERTY_DATE                                                    = "date";
    public static final String                   PROPERTY_MEDIA_INDEX                                             = "mediaindex";
    public static final String                   PROPERTY_MEDIA_ID                                                = "mediaid";
    public static final String                   PROPERTY_BITRATE                                                 = "bitrate";
    public static final String                   PROPERTY_TWEET_TEXT                                              = "tweet_text";
    public static final String                   PROPERTY_FILENAME_FROM_CRAWLER                                   = "crawlerfilename";
    public static final String                   PROPERTY_VIDEO_DIRECT_URLS_ARE_AVAILABLE_VIA_API_EXTENDED_ENTITY = "video_direct_urls_are_available_via_api_extended_entity";
    private static LinkedHashMap<String, Object> USER_CACHE                                                       = new LinkedHashMap<String, Object>() {
                                                                                                                      protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                                                                                                                          return size() > 500;
                                                                                                                      };
                                                                                                                  };

    protected DownloadLink createDownloadlink(final String link, final String tweetid) {
        final DownloadLink ret = super.createDownloadlink(link);
        ret.setProperty("tweetid", tweetid);
        return ret;
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "twitter.com" });
        return ret;
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
            String regex = "https?://(?:www\\.)?" + buildHostsPatternPart(domains);
            regex += "/(?:";
            regex += "[A-Za-z0-9_\\-]+/status/\\d+";
            regex += "|i/videos/tweet/\\d+";
            regex += "|[A-Za-z0-9_\\-]{2,}(?:/(?:media|likes))?(\\?.*)?";
            regex += ")";
            ret.add(regex);
        }
        return ret.toArray(new String[0]);
    }

    @SuppressWarnings("deprecation")
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, final ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setAllowedResponseCodes(new int[] { 429 });
        final String newURL = param.getCryptedUrl().replaceFirst("https?://(www\\.|mobile\\.)?twitter\\.com/", "https://" + this.getHost() + "/");
        if (!newURL.equals(param.getCryptedUrl())) {
            logger.info("Currected URL: " + newURL);
            param.setCryptedUrl(newURL);
        }
        br.setFollowRedirects(true);
        /* Some profiles can only be accessed if they accepted others as followers --> Login if the user has added his twitter account */
        final Account account = getUserLogin(false);
        if (account != null) {
            logger.info("Account available and we're logged in");
        } else {
            logger.info("No account available or login failed");
        }
        if (param.getCryptedUrl().matches(TYPE_CARD)) {
            getPage(param.getCryptedUrl());
            if (br.getRequest().getHttpConnection().getResponseCode() == 403 || br.getRequest().getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.containsHTML("class=\"ProtectedTimeline\"")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String tweetID = new Regex(param.getCryptedUrl(), TYPE_CARD).getMatch(0);
            /* First check for external urls */
            decryptedLinks.addAll(this.findEmbedUrls(null));
            String externID = br.getRegex("u\\-linkClean js\\-openLink\" href=\"(https?://t\\.co/[^<>\"]*?)\"").getMatch(0);
            if (externID == null) {
                externID = br.getRegex("\"card_ur(?:i|l)\"\\s*:\\s*\"(https?[^<>\"]*?)\"").getMatch(0);
            }
            if (externID != null) {
                decryptedLinks.add(this.createDownloadlink(externID));
                return decryptedLinks;
            }
            if (decryptedLinks.isEmpty()) {
                String dllink = br.getRegex("playlist\\&quot;:\\[\\{\\&quot;source\\&quot;:\\&quot;(https[^<>\"]*?\\.(?:webm|mp4))").getMatch(0);
                if (dllink == null) {
                    logger.info("dllink == null, abend ");
                    return null;
                }
                dllink = dllink.replace("\\", "");
                final String filename = tweetID + "_" + new Regex(dllink, "([^/]+\\.[a-z0-9]+)$").getMatch(0);
                final DownloadLink dl = this.createDownloadlink(dllink, tweetID);
                dl.setProperty(PROPERTY_FILENAME_FROM_CRAWLER, filename);
                dl.setName(filename);
                dl.setAvailable(true);
                decryptedLinks.add(dl);
            }
        } else if (param.getCryptedUrl().matches(jd.plugins.hoster.TwitterCom.TYPE_VIDEO_EMBED)) {
            return crawlAPISingleTweet(param, new Regex(param.getCryptedUrl(), jd.plugins.hoster.TwitterCom.TYPE_VIDEO_EMBED).getMatch(0), account);
        } else if (param.getCryptedUrl().matches(TYPE_USER_POST)) {
            final boolean prefer_mobile_website = false;
            if (prefer_mobile_website) {
                /* Single Tweet */
                if (switchtoMobile()) {
                    crawlTweetViaMobileWebsite(decryptedLinks, param.getCryptedUrl(), null);
                    return decryptedLinks;
                }
                /* Fallback to API/normal website */
            }
            final String tweetID = new Regex(param.getCryptedUrl(), TYPE_USER_POST).getMatch(1);
            return crawlAPISingleTweet(param, tweetID, account);
        } else {
            return crawlUserViaAPI(param, account);
        }
        if (decryptedLinks.size() == 0) {
            logger.info("Could not find any results for: " + param.getCryptedUrl());
            return decryptedLinks;
        }
        return decryptedLinks;
    }

    @Override
    public void init() {
        super.init();
        Browser.setRequestIntervalLimitGlobal("twimg.com", true, 500);
        Browser.setRequestIntervalLimitGlobal("api.twitter.com", true, 500);
    }

    @Deprecated
    private boolean switchtoMobile() throws IOException {
        /*
         * 2020-01-30: They're now using a json web-API for which we cannot easily get the auto parameters --> Try mobile website as
         * fallback ...
         */
        logger.info("Trying to switch to mobile website");
        final Form nojs_form = br.getFormbyActionRegex(".+nojs_router.+");
        if (nojs_form != null) {
            logger.info("Switching to mobile website");
            br.submitForm(nojs_form);
            logger.warning("Successfully switched to to mobile website");
            return true;
        } else {
            logger.warning("Failed to switch to mobile website");
            return false;
        }
    }

    private ArrayList<DownloadLink> crawlAPISingleTweet(final CryptedLink param, final String tweetID, final Account account) throws Exception {
        if (tweetID == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        logger.info("Crawling API tweet");
        prepareAPI(this.br, account);
        final boolean useNewMethod = true; /* 2021-06-15 */
        if (useNewMethod) {
            br.getPage("https://api.twitter.com/1.1/statuses/show/" + tweetID + ".json?cards_platform=Web-12&include_reply_count=1&include_cards=1&include_user_entities=0&tweet_mode=extended");
            handleErrorsAPI(this.br);
            final Map<String, Object> tweet = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            return crawlTweetMap(tweet);
        } else {
            br.getPage("https://api.twitter.com/2/timeline/conversation/" + tweetID + ".json?include_profile_interstitial_type=1&include_blocking=1&include_blocked_by=1&include_followed_by=1&include_want_retweets=1&include_mute_edge=1&include_can_dm=1&include_can_media_tag=1&skip_status=1&cards_platform=Web-12&include_cards=1&include_composer_source=true&include_ext_alt_text=true&include_reply_count=1&tweet_mode=extended&include_entities=true&include_user_entities=true&include_ext_media_color=true&include_ext_media_availability=true&send_error_codes=true&simple_quoted_tweets=true&count=20&ext=mediaStats%2CcameraMoment");
            final Map<String, Object> root = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
            final Map<String, Object> tweet = (Map<String, Object>) JavaScriptEngineFactory.walkJson(root, "globalObjects/tweets/" + tweetID);
            return crawlTweetMap(tweet);
        }
    }

    public static Browser prepAPIHeaders(final Browser br) {
        br.getHeaders().put("Authorization", "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA");
        final String csrftoken = br.getCookie("twitter.com", jd.plugins.hoster.TwitterCom.COOKIE_KEY_LOGINED_CSRFTOKEN, Cookies.NOTDELETEDPATTERN);
        if (csrftoken != null) {
            /* Indicates that the user is loggedin. */
            br.getHeaders().put("x-csrf-token", csrftoken);
        } else {
            br.getHeaders().put("x-csrf-token", "undefined");
        }
        br.getHeaders().put("x-twitter-active-user", "yes");
        br.getHeaders().put("x-twitter-client-language", "de");
        br.getHeaders().put("x-twitter-polling", "true");
        return br;
    }

    private Browser prepareAPI(final Browser br, final Account account) throws PluginException, IOException {
        /* 2020-02-03: Static authtoken */
        prepAPIHeaders(br);
        if (account == null) {
            /* Gues token is only needed for anonymous users */
            getAndSetGuestToken(this, br);
        }
        return br;
    }

    public static boolean resetGuestToken() {
        synchronized (GUEST_TOKEN) {
            final boolean ret = GUEST_TOKEN.getAndSet(null) != null;
            if (ret) {
                GUEST_TOKEN_TS.set(-1);
            }
            return ret;
        }
    }

    public static String getAndSetGuestToken(Plugin plugin, final Browser br) throws PluginException, IOException {
        synchronized (GUEST_TOKEN) {
            String guest_token = GUEST_TOKEN.get();
            final long age = Time.systemIndependentCurrentJVMTimeMillis() - GUEST_TOKEN_TS.get();
            if (guest_token == null || age > (30 * 60 * 1000l)) {
                plugin.getLogger().info("Generating new guest_token:age:" + age);
                guest_token = generateNewGuestToken(br);
                if (StringUtils.isEmpty(guest_token)) {
                    plugin.getLogger().warning("Failed to find guest_token");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                } else {
                    GUEST_TOKEN.set(guest_token);
                    GUEST_TOKEN_TS.set(Time.systemIndependentCurrentJVMTimeMillis());
                    plugin.getLogger().warning("Found new guest_token:" + guest_token);
                }
            } else {
                plugin.getLogger().info("Re-using existing guest-token:" + guest_token + "|age:" + age);
            }
            br.getHeaders().put("x-guest-token", guest_token);
            return guest_token;
        }
    }

    public static String generateNewGuestToken(final Browser br) throws IOException {
        final Browser brc = br.cloneBrowser();
        brc.postPage("https://api.twitter.com/1.1/guest/activate.json", "");
        /** TODO: Save guest_token throughout session so we do not generate them so frequently */
        return PluginJSonUtils.getJson(brc, "guest_token");
    }

    /**
     * Crawls single media objects obtained via API.
     *
     * @throws MalformedURLException
     */
    private ArrayList<DownloadLink> crawlTweetMap(final Map<String, Object> tweet) throws MalformedURLException {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String userIDStr = tweet.get("user_id_str").toString();
        final String tweetID = tweet.get("id_str").toString();
        final Object userInContextOfTweet = tweet.get("user");
        final Map<String, Object> user;
        if (userInContextOfTweet != null) {
            user = (Map<String, Object>) tweet.get("user");
        } else {
            user = this.getCachedUserInfo(userIDStr);
        }
        String username = (String) user.get("screen_name");
        final String formattedDate = formatTwitterDate((String) tweet.get("created_at"));
        final String tweetText = (String) tweet.get("full_text");
        final FilePackage fp = FilePackage.getInstance();
        fp.setProperty(LinkCrawler.PACKAGE_ALLOW_INHERITANCE, true);
        fp.setProperty(LinkCrawler.PACKAGE_ALLOW_MERGE, true);
        if (userInContextOfTweet != null) {
            /* We're crawling a single tweet -> Set date + username as packagename. */
            username = (String) user.get("screen_name");
            fp.setName(formattedDate + "_" + username);
            if (!StringUtils.isEmpty(tweetText)) {
                fp.setComment(tweetText);
            }
        } else {
            /* We're crawling a tweet as part of the complete timeline -> Set username as packagename. */
            fp.setName(username);
            final String userDescription = (String) user.get("description");
            if (!StringUtils.isEmpty(userDescription)) {
                fp.setComment(userDescription);
            }
        }
        TwitterConfigInterface cfg = PluginJsonConfig.get(jd.plugins.hoster.TwitterCom.TwitterConfigInterface.class);
        final List<Map<String, Object>> medias = (List<Map<String, Object>>) JavaScriptEngineFactory.walkJson(tweet, "extended_entities/media");
        final String vmapURL = (String) JavaScriptEngineFactory.walkJson(tweet, "card/binding_values/amplify_url_vmap/string_value");
        if (medias != null && !medias.isEmpty()) {
            int mediaIndex = 0;
            for (final Map<String, Object> media : medias) {
                final String type = (String) media.get("type");
                try {
                    final DownloadLink dl;
                    String filename = null;
                    if (type.equals("video") || type.equals("animated_gif")) {
                        /* Find highest video quality */
                        /* animated_gif will usually only have one .mp4 version available with bitrate "0". */
                        int highestBitrate = -1;
                        final List<Map<String, Object>> videoVariants = (List<Map<String, Object>>) JavaScriptEngineFactory.walkJson(media, "video_info/variants");
                        String streamURL = null;
                        String hlsMaster = null;
                        for (final Map<String, Object> videoVariant : videoVariants) {
                            final String content_type = (String) videoVariant.get("content_type");
                            if (content_type.equalsIgnoreCase("video/mp4")) {
                                final int bitrate = ((Number) videoVariant.get("bitrate")).intValue();
                                if (bitrate > highestBitrate) {
                                    highestBitrate = bitrate;
                                    streamURL = (String) videoVariant.get("url");
                                }
                            } else if (content_type.equalsIgnoreCase("application/x-mpegURL")) {
                                hlsMaster = (String) videoVariant.get("url");
                            } else {
                                logger.info("Skipping unsupported video content_type: " + content_type);
                            }
                        }
                        if (StringUtils.isEmpty(streamURL)) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        }
                        dl = this.createDownloadlink(createVideourl(tweetID));
                        if (cfg.isUseOriginalFilenames()) {
                            filename = tweetID + "_" + Plugin.getFileNameFromURL(new URL(streamURL));
                        } else if (medias.size() > 1) {
                            filename = formattedDate + "_" + username + "_" + tweetID + "_" + mediaIndex + ".mp4";
                        } else {
                            filename = formattedDate + "_" + username + "_" + tweetID + ".mp4";
                        }
                        dl.setProperty(PROPERTY_BITRATE, highestBitrate);
                        dl.setProperty(jd.plugins.hoster.TwitterCom.PROPERTY_DIRECTURL, streamURL);
                        if (!StringUtils.isEmpty(hlsMaster)) {
                            dl.setProperty(jd.plugins.hoster.TwitterCom.PROPERTY_DIRECTURL_hls_master, hlsMaster);
                        }
                        dl.setProperty(PROPERTY_VIDEO_DIRECT_URLS_ARE_AVAILABLE_VIA_API_EXTENDED_ENTITY, true);
                    } else if (type.equals("photo")) {
                        final String url = (String) media.get("media_url"); /* Also available as "media_url_https" */
                        if (StringUtils.isEmpty(url)) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        }
                        dl = this.createDownloadlink(url);
                        if (cfg.isUseOriginalFilenames()) {
                            filename = tweetID + "_" + Plugin.getFileNameFromURL(new URL(url));
                        } else if (medias.size() > 1) {
                            filename = formattedDate + "_" + username + "_" + tweetID + "_" + mediaIndex + Plugin.getFileNameExtensionFromURL(url);
                        } else {
                            filename = formattedDate + "_" + username + "_" + tweetID + Plugin.getFileNameExtensionFromURL(url);
                        }
                    } else {
                        /* Unknown type -> This should never happen! */
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unknown media type:" + type);
                    }
                    if (filename != null) {
                        dl.setFinalFileName(filename);
                        dl.setProperty(PROPERTY_FILENAME_FROM_CRAWLER, filename);
                    }
                    dl.setAvailable(true);
                    dl.setProperty(PROPERTY_USERNAME, username);
                    dl.setProperty(PROPERTY_DATE, formattedDate);
                    dl.setProperty(PROPERTY_MEDIA_INDEX, mediaIndex);
                    dl.setProperty(PROPERTY_MEDIA_ID, media.get("id_str").toString());
                    if (!StringUtils.isEmpty(tweetText)) {
                        dl.setProperty(PROPERTY_TWEET_TEXT, tweetText);
                    }
                    if (dl.getFinalFileName() != null) {
                        dl.setProperty(PROPERTY_FILENAME_FROM_CRAWLER, dl.getFinalFileName());
                    }
                    if (fp != null) {
                        dl._setFilePackage(fp);
                    }
                    decryptedLinks.add(dl);
                    distribute(dl);
                } catch (PluginException e) {
                    logger.log(e);
                }
                mediaIndex += 1;
            }
        } else if (!StringUtils.isEmpty(vmapURL)) {
            /* Fallback handling for very old (???) content */
            /* Expect such URLs which our host plugin can handle: https://video.twimg.com/amplify_video/vmap/<numbers>.vmap */
            final DownloadLink singleVideo = this.createDownloadlink(vmapURL);
            final String finalFilename = formattedDate + "_" + username + "_" + tweetID + ".mp4";
            singleVideo.setFinalFileName(finalFilename);
            singleVideo.setProperty(PROPERTY_FILENAME_FROM_CRAWLER, finalFilename);
            singleVideo.setProperty(PROPERTY_VIDEO_DIRECT_URLS_ARE_AVAILABLE_VIA_API_EXTENDED_ENTITY, false);
            singleVideo.setProperty(PROPERTY_USERNAME, username);
            singleVideo.setProperty(PROPERTY_DATE, formattedDate);
            singleVideo.setProperty(PROPERTY_MEDIA_INDEX, 0);
            if (!StringUtils.isEmpty(tweetText)) {
                singleVideo.setProperty(PROPERTY_TWEET_TEXT, tweetText);
            }
            singleVideo.setAvailable(true);
            if (fp != null) {
                singleVideo._setFilePackage(fp);
            }
            decryptedLinks.add(singleVideo);
            distribute(singleVideo);
        }
        int itemsSkippedDueToPluginSettings = 0;
        if (!StringUtils.isEmpty(tweetText)) {
            final String[] urlsInPostText = HTMLParser.getHttpLinks(tweetText, br.getURL());
            if (cfg.isCrawlURLsInsideTweetText() && urlsInPostText.length > 0) {
                for (final String url : urlsInPostText) {
                    final DownloadLink dl = this.createDownloadlink(url);
                    if (fp != null) {
                        dl._setFilePackage(fp);
                    }
                    decryptedLinks.add(dl);
                    distribute(dl);
                }
            } else if (urlsInPostText != null) {
                itemsSkippedDueToPluginSettings += urlsInPostText.length;
            }
            if (cfg.isAddTweetTextAsTextfile()) {
                final DownloadLink text = this.createDownloadlink(createTwitterPostURL(username, tweetID));
                final String finalFilename = formattedDate + "_" + username + "_" + tweetID + ".txt";
                text.setFinalFileName(finalFilename);
                try {
                    text.setDownloadSize(tweetText.getBytes("UTF-8").length);
                } catch (final UnsupportedEncodingException ignore) {
                    ignore.printStackTrace();
                }
                text.setProperty(PROPERTY_FILENAME_FROM_CRAWLER, finalFilename);
                text.setProperty(PROPERTY_USERNAME, username);
                text.setProperty(PROPERTY_DATE, formattedDate);
                text.setProperty(PROPERTY_MEDIA_INDEX, 0);
                text.setProperty(PROPERTY_TWEET_TEXT, tweetText);
                text.setAvailable(true);
                if (fp != null) {
                    text._setFilePackage(fp);
                }
                decryptedLinks.add(text);
                distribute(text);
            } else {
                itemsSkippedDueToPluginSettings++;
            }
        }
        /* Logger just in case nothing was added. */
        if (decryptedLinks.isEmpty()) {
            if (itemsSkippedDueToPluginSettings == 0) {
                logger.info("Failed to find any crawlable content in tweet: " + tweetID);
            } else {
                logger.info("Failed to find any crawlable content because of user settings. Crawlable but skipped " + itemsSkippedDueToPluginSettings + " items due to users' plugin settings.");
            }
        }
        return decryptedLinks;
    }

    private static String formatTwitterDate(String created_at) {
        if (created_at == null) {
            return null;
        }
        try {
            created_at = created_at.substring(created_at.indexOf(" ") + 1, created_at.length());
            final String targetFormat = "yyyy-MM-dd";
            final long timestamp = TimeFormatter.getMilliSeconds(created_at, "MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
            if (timestamp == -1) {
                throw new Exception("TimeFormatter failed for:" + created_at);
            }
            final SimpleDateFormat formatter = new SimpleDateFormat(targetFormat);
            return formatter.format(new Date(timestamp));
        } catch (final Throwable e) {
            /* Fallback */
            return created_at;
        }
    }

    @Deprecated
    private void crawlTweetViaMobileWebsite(final ArrayList<DownloadLink> decryptedLinks, final String tweetURL, final FilePackage fp) throws IOException {
        logger.info("Crawling mobile website tweet");
        final String tweet_id = new Regex(param.getCryptedUrl(), "/(?:tweet|status)/(\\d+)").getMatch(0);
        if (br.containsHTML("/status/" + tweet_id + "/video/1")) {
            /* Video */
            final DownloadLink dl = createDownloadlink(createVideourl(tweet_id));
            if (fp != null) {
                dl._setFilePackage(fp);
            }
            decryptedLinks.add(dl);
            distribute(dl);
        } else if (br.containsHTML("/tweet_video_thumb/")) {
            /* .gif --> Can be downloaded as .mp4 video */
            final DownloadLink dl = createDownloadlink(createVideourl(tweet_id));
            decryptedLinks.add(dl);
            if (fp != null) {
                dl._setFilePackage(fp);
            }
            distribute(dl);
        } else {
            /* Picture or text */
            final String[] regexes = { "(https?://[^<>\"]+/media/[A-Za-z0-9\\-_]+(\\.(?:jpg|png|gif):[a-z]+))" };
            for (final String regex : regexes) {
                final String[] alllinks = br.getRegex(regex).getColumn(0);
                if (alllinks != null && alllinks.length > 0) {
                    for (String alink : alllinks) {
                        final Regex fin_al = new Regex(alink, "https?://[^<>\"]+/[^/]+/([A-Za-z0-9\\-_]+)\\.([a-z0-9]+)(:[a-z]+)?$");
                        final String servername = fin_al.getMatch(0);
                        final String ending = fin_al.getMatch(1);
                        final String quality = fin_al.getMatch(2);
                        final String final_filename = tweet_id + "_" + servername + "." + ending;
                        alink = Encoding.htmlDecode(alink.trim());
                        /* Always get the best quality. Possible qualities: thumb, small, medium, large, orig */
                        if (!quality.equalsIgnoreCase("large")) {
                            alink = alink.replace(quality, ":large");
                        }
                        final DownloadLink dl = createDownloadlink(alink, tweet_id);
                        dl.setAvailable(true);
                        dl.setProperty(PROPERTY_FILENAME_FROM_CRAWLER, final_filename);
                        dl.setName(final_filename);
                        if (fp != null) {
                            dl._setFilePackage(fp);
                        }
                        decryptedLinks.add(dl);
                        distribute(dl);
                    }
                }
            }
            if (decryptedLinks.isEmpty()) {
                logger.warning("Found nothing - either only text or plugin broken :(");
                decryptedLinks.add(this.createOfflinelink(param.getCryptedUrl()));
            }
        }
    }

    private ArrayList<DownloadLink> crawlUserViaAPI(final CryptedLink param, final Account account) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        logger.info("Crawling user profile via API");
        final String username = new Regex(param.getCryptedUrl(), TYPE_USER_ALL).getMatch(0);
        if (username == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        this.prepareAPI(br, account);
        final boolean use_old_api_to_get_userid = true;
        final Map<String, Object> user;
        if (use_old_api_to_get_userid) {
            /* https://developer.twitter.com/en/docs/accounts-and-users/follow-search-get-users/api-reference/get-users-show */
            /* https://developer.twitter.com/en/docs/twitter-api/rate-limits */
            /* per 15 mins window, 300 per app, 900 per user */
            br.getPage("https://api.twitter.com/1.1/users/lookup.json?screen_name=" + username);
            if (br.getHttpConnection().getResponseCode() == 403) {
                /* {"errors":[{"code":22,"message":"Not authorized to view the specified user."}]} */
                throw new AccountRequiredException();
            } else if (br.getHttpConnection().getResponseCode() == 404) {
                /* {"errors":[{"code":17,"message":"No user matches for specified terms."}]} */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final Object responseO = JSonStorage.restoreFromString(br.toString(), TypeRef.OBJECT);
            if (!(responseO instanceof List)) {
                logger.warning("Unknown API error/response");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final List<Map<String, Object>> users = (List<Map<String, Object>>) responseO;
            user = users.get(0);
        } else {
            br.getPage("https://api.twitter.com/graphql/DO_NOT_USE_ATM_2020_02_05/UserByScreenName?variables=%7B%22screen_name%22%3A%22" + username + "%22%2C%22withHighlightedLabel%22%3Afalse%7D");
            final Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
            user = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "data/user");
            // userID = (String) user.get("rest_id");
        }
        final String userID = user.get("id_str").toString();
        /* = number of tweets */
        final int statuses_count = ((Number) user.get("statuses_count")).intValue();
        /* = number of tweets containing media (can be lower than "statuses_count") */
        final int media_count = ((Number) user.get("media_count")).intValue();
        if (statuses_count == 0) {
            /* Profile contains zero tweets! */
            decryptedLinks.add(getDummyErrorProfileContainsNoTweets(username));
            return decryptedLinks;
        }
        /* Add user-object to cache */
        synchronized (USER_CACHE) {
            USER_CACHE.put(userID, user);
        }
        final boolean setting_force_grab_media = PluginJsonConfig.get(jd.plugins.hoster.TwitterCom.TwitterConfigInterface.class).isForceGrabMediaOnlyEnabled();
        /* Grab only content posted by user or grab everything from his timeline e.g. also re-tweets. */
        final String content_type;
        Integer maxCount = null;
        final int expected_items_per_page = 20;
        String nextCursor = null;
        final UrlQuery query = new UrlQuery();
        query.append("include_profile_interstitial_type", "1", false);
        query.append("include_blocking", "1", false);
        query.append("include_blocked_by", "1", false);
        query.append("include_followed_by", "1", false);
        query.append("include_want_retweets", "1", false);
        query.append("include_mute_edge", "1", false);
        query.append("include_can_dm", "1", false);
        query.append("include_can_media_tag", "1", false);
        query.append("skip_status", "1", false);
        query.append("cards_platform", "Web-12", false);
        query.append("include_cards", "1", false);
        /* 2020-08-24: Not required anymore */
        // query.append("include_composer_source", "true", false);
        query.append("include_quote_count", "true", false);
        query.append("include_ext_alt_text", "true", false);
        query.append("include_reply_count", "1", false);
        query.append("tweet_mode", "extended", false);
        query.append("include_entities", "true", false);
        query.append("include_user_entities", "true", false);
        query.append("include_ext_media_color", "true", false);
        query.append("include_ext_media_availability", "true", false);
        query.append("include_ext_sensitive_media_warning", "true", false);
        query.append("send_error_codes", "true", false);
        query.append("simple_quoted_tweet", "true", false);
        if (param.getCryptedUrl().endsWith("/likes")) {
            /* Crawl all liked items of a user */
            logger.info("Crawling all liked items of user " + username);
            if (account == null) {
                logger.info("Account required to crawl all liked items of a user");
                throw new DecrypterRetryException(RetryReason.NO_ACCOUNT, "ACCOUNT_REQUIRED_TO_CRAWL_LIKED_ITEMS_OF_PROFILE_" + username, "Account is required to crawl liked items of profiles.");
            }
            content_type = "favorites";
            final int favoritesCount = ((Number) user.get("favourites_count")).intValue();
            if (favoritesCount == 0) {
                decryptedLinks.add(getDummyErrorProfileContainsNoLikedItems(username));
                return decryptedLinks;
            }
            maxCount = favoritesCount;
            query.append("simple_quoted_tweets", "true", false);
            query.append("sorted_by_time", "true", false);
            // fpname += " - likes";
        } else if (param.getCryptedUrl().endsWith("/media") || setting_force_grab_media) {
            logger.info("Crawling self posted media only from user: " + username);
            if (media_count == 0) {
                throw new DecrypterRetryException(RetryReason.PLUGIN_SETTINGS, "PROFILE_CONTAINS_NO_MEDIA_POSTS_" + username, "Profile " + username + " contains no media-posts but user wants to crawl media posts only.");
            }
            content_type = "media";
            maxCount = media_count;
        } else {
            logger.info("Crawling ALL media of a user e.g. also retweets | user: " + username);
            content_type = "profile";
            maxCount = statuses_count;
            query.add("include_tweet_replies", "false");
        }
        final UrlQuery addedURLQuery = UrlQuery.parse(param.getCryptedUrl());
        Number maxTweetsToCrawl = null;
        final String maxTweetsToCrawlStr = addedURLQuery.get("maxitems");
        final String maxTweetDateStr = addedURLQuery.get("max_date");
        long crawlUntilTimestamp = -1;
        if (maxTweetsToCrawlStr != null) {
            if (maxTweetsToCrawlStr.matches("\\d+")) {
                maxTweetsToCrawl = Integer.parseInt(maxTweetsToCrawlStr);
            } else {
                logger.info("Ignoring user defined 'maxitems' parameter because of invalid input format: " + maxTweetsToCrawlStr);
            }
        }
        if (maxTweetDateStr != null) {
            try {
                crawlUntilTimestamp = TimeFormatter.getMilliSeconds(maxTweetDateStr, "yyyy-MM-dd", Locale.ENGLISH);
            } catch (final Throwable ignore) {
                logger.info("Ignoring user defined 'max_date' parameter because of invalid input format: " + maxTweetDateStr);
            }
        }
        query.append("userId", userID, false);
        query.append("count", expected_items_per_page + "", false);
        query.append("ext", "mediaStats,cameraMoment", true);
        final HashSet<String> cursorDupes = new HashSet<String>();
        int totalCrawledTweetsCount = 0;
        int page = 1;
        tweetTimeline: do {
            logger.info("Crawling page " + page);
            final UrlQuery thisquery = query;
            if (!StringUtils.isEmpty(nextCursor)) {
                thisquery.append("cursor", nextCursor, true);
            }
            final String url = String.format("https://api.twitter.com/2/timeline/%s/%s.json", content_type, userID);
            br.getPage(url + "?" + thisquery.toString());
            handleErrorsAPI(this.br);
            final Map<String, Object> root = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
            final Map<String, Object> globalObjects = (Map<String, Object>) root.get("globalObjects");
            final Map<String, Object> users = (Map<String, Object>) globalObjects.get("users");
            final Iterator<Entry<String, Object>> userIterator = users.entrySet().iterator();
            synchronized (USER_CACHE) {
                USER_CACHE.clear();
                while (userIterator.hasNext()) {
                    final Entry<String, Object> entry = userIterator.next();
                    USER_CACHE.put(entry.getKey(), entry.getValue());
                }
            }
            final List<Object> pagination_info = (List<Object>) JavaScriptEngineFactory.walkJson(root, "timeline/instructions/{0}/addEntries/entries");
            final Map<String, Object> tweetMap = (Map<String, Object>) globalObjects.get("tweets");
            if (tweetMap == null || tweetMap.isEmpty()) {
                logger.info("Stopping because: Found 0 tweets on current page");
                break;
            }
            final Iterator<Entry<String, Object>> iterator = tweetMap.entrySet().iterator();
            String lastCreatedAtDateStr = null;
            while (iterator.hasNext()) {
                final Map<String, Object> tweet = (Map<String, Object>) iterator.next().getValue();
                decryptedLinks.addAll(crawlTweetMap(tweet));
                totalCrawledTweetsCount++;
                lastCreatedAtDateStr = (String) tweet.get("created_at");
                final long currentTweetTimestamp = TimeFormatter.getMilliSeconds(lastCreatedAtDateStr, "EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
                /* Check some abort conditions */
                if (maxTweetsToCrawl != null && totalCrawledTweetsCount >= maxTweetsToCrawl.intValue()) {
                    logger.info("Stopping because: Reached user defined max items count: " + maxTweetsToCrawl);
                    break tweetTimeline;
                } else if (crawlUntilTimestamp != -1 && currentTweetTimestamp > crawlUntilTimestamp) {
                    logger.info("Stopping because: Reached max desired tweet age of: " + maxTweetDateStr);
                    break tweetTimeline;
                }
            }
            logger.info("Crawled page " + page + " | Tweets crawled so far: " + totalCrawledTweetsCount + "/" + maxCount.intValue() + " | lastCreatedAtDateStr = " + lastCreatedAtDateStr);
            if (tweetMap.size() < expected_items_per_page) {
                /* We'll try anyways and let it run into our fail-safe for when a page contains zero items. */
                logger.info(String.format("Warning: Current page contains less than %d objects --> Reached the end?", expected_items_per_page));
            }
            /* Done - now try to find string required to access next page. */
            try {
                final Map<String, Object> pagination_info_entries = (Map<String, Object>) pagination_info.get(pagination_info.size() - 1);
                final String entryId = (String) pagination_info_entries.get("entryId");
                if (!entryId.contains("cursor-bottom")) {
                    logger.info("Stopping because: Found wrong cursor object --> Plugin probably needs update");
                    break;
                }
                nextCursor = (String) JavaScriptEngineFactory.walkJson(pagination_info_entries, "content/operation/cursor/value");
                if (StringUtils.isEmpty(nextCursor)) {
                    logger.info("Stopping because: Failed to find nextCursor");
                    break;
                } else if (!cursorDupes.add(nextCursor)) {
                    logger.info("Stopping because: We've already crawled current cursor: " + nextCursor);
                    break;
                }
                logger.info("nextCursor = " + nextCursor);
            } catch (final Throwable e) {
                logger.log(e);
                logger.info("Stopping because: Failed to get nextCursor (Exception occured)");
                break;
            }
            page++;
            this.sleep(3000l, param);
        } while (!this.isAbort());
        logger.info("Done after " + page + " pages");
        if (decryptedLinks.isEmpty()) {
            logger.info("Found nothing --> Either user has posts containing media or those can only be viewed by certain users or only when logged in (explicit content)");
            if (account == null) {
                throw new DecrypterRetryException(RetryReason.NO_ACCOUNT, "PROFILE_CONTAINS_ONLY_EXPLICIT_CONTENT_ACCOUNT_REQUIRED_" + username, "Profile " + username + " contains only explicit content which can only be viewed when logged in --> Add a twitter account to JDownloader and try again!");
            } else {
                decryptedLinks.add(getDummyErrorProfileContainsNoDownloadableContent(username));
            }
        }
        return decryptedLinks;
    }

    private DownloadLink getDummyErrorProfileContainsNoLikedItems(final String username) {
        final DownloadLink dummy = this.createOfflinelink(param.getCryptedUrl(), "PROFILE_CONTAINS_NO_LIKES_" + username, "The profile " + username + " does not contain any liked items.");
        return dummy;
    }

    private DownloadLink getDummyErrorProfileContainsNoTweets(final String username) {
        final DownloadLink dummy = this.createOfflinelink(param.getCryptedUrl(), "PROFILE_CONTAINS_NO_TWEETS_" + username, "The profile " + username + " does not contain any tweets.");
        return dummy;
    }

    private DownloadLink getDummyErrorProfileContainsNoDownloadableContent(final String username) {
        final DownloadLink dummy = this.createOfflinelink(param.getCryptedUrl(), "PROFILE_CONTAINS_NO_DOWNLOADABLE_CONTENT_" + username, "The profile " + username + " does not contain any downloadable content. Check your twitter plugin settings maybe you've turned off some of the crawlable content.");
        return dummy;
    }

    /**
     * https://developer.twitter.com/en/support/twitter-api/error-troubleshooting </br>
     * Scroll down to "Twitter API error codes"
     */
    private void handleErrorsAPI(final Browser br) throws Exception {
        Map<String, Object> entries = null;
        try {
            entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
        } catch (final Exception e) {
            /* Check for some pure http error-responsecodes. */
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.getHttpConnection().getResponseCode() == 429) {
                throw new DecrypterRetryException(RetryReason.FILE_NOT_FOUND, "Rate-Limit reached");
            } else if (br.getHttpConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        }
        final Object errorsO = entries.get("errors");
        if (errorsO != null) {
            final List<Map<String, Object>> errors = (List<Map<String, Object>>) errorsO;
            for (final Map<String, Object> error : errors) {
                final int code = ((Number) error.get("code")).intValue();
                switch (code) {
                case 34:
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                case 63:
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                case 109:
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                case 144:
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                case 325:
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                case 421:
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                case 220:
                    /* {"errors":[{"code":220,"message":"Your credentials do not allow access to this resource."}]} */
                    throw new AccountRequiredException();
                default:
                    throw new DecrypterRetryException(RetryReason.FILE_NOT_FOUND, error.get("message").toString());
                }
            }
        }
    }

    /* 2020-01-30: Mobile website will only show 1 tweet per page */
    @Deprecated
    private ArrayList<DownloadLink> crawlUserViaMobileWebsite(final String parameter) throws IOException {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        logger.info("Crawling mobile website user");
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        } else if (!this.switchtoMobile()) {
            logger.warning("Unable to crawl: Failed to switch to mobile website");
            return decryptedLinks;
        }
        final String username = new Regex(parameter, "https?://[^/]+/([^/]+)").getMatch(0);
        int index = 0;
        String nextURL = null;
        final boolean crawl_tweets_separately = true;
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(username);
        do {
            logger.info("Crawling page " + (index + 1));
            if (nextURL != null) {
                br.getPage(nextURL);
            }
            final String current_tweet_id = br.getRegex("name=\"tweet_(\\d+)\"").getMatch(0);
            if (current_tweet_id == null) {
                logger.warning("Failed to find current_tweet_id");
                break;
            }
            final String tweet_url = String.format("https://twitter.com/%s/status/%s", username, current_tweet_id);
            if (crawl_tweets_separately) {
                /* These URLs will go back into the crawler to get crawled separately */
                final DownloadLink dl = this.createDownloadlink(tweet_url);
                decryptedLinks.add(dl);
                distribute(dl);
            } else {
                crawlTweetViaMobileWebsite(decryptedLinks, tweet_url, fp);
            }
            index++;
            nextURL = br.getRegex("(/[^/]+/media/grid\\?idx=" + index + ")").getMatch(0);
        } while (nextURL != null && !this.isAbort());
        logger.info(String.format("Done after %d pages", index));
        return decryptedLinks;
    }

    /** Retrns cached map containing information about user according to given userID. */
    private Map<String, Object> getCachedUserInfo(final String userID) {
        if (USER_CACHE.containsKey(userID)) {
            return (Map<String, Object>) USER_CACHE.get(userID);
        } else {
            return null;
        }
    }

    protected void getPage(final Browser br, final String url) throws Exception {
        super.getPage(br, url);
        if (br.getHttpConnection().getResponseCode() == 429) {
            logger.info("Error 429 too many requests - add less URLs and/or perform a reconnect!");
        }
    }

    protected void getPage(final String url) throws Exception {
        getPage(br, url);
    }

    public static String createVideourl(final String tweetID) {
        return "https://twitter.com/i/videos/tweet/" + tweetID;
    }

    public static String createTwitterPostURL(final String user, final String tweetID) {
        return "https://twitter.com/" + user + "/status/" + tweetID;
    }

    /** Log in the account of the hostplugin */
    @SuppressWarnings({ "static-access" })
    private Account getUserLogin(final boolean force) throws Exception {
        final PluginForHost hostPlugin = getNewPluginForHostInstance("twitter.com");
        final Account aa = AccountController.getInstance().getValidAccount("twitter.com");
        if (aa == null) {
            return null;
        }
        try {
            ((jd.plugins.hoster.TwitterCom) hostPlugin).login(this, br, aa, force);
            return aa;
        } catch (final PluginException e) {
            logger.log(e);
            return null;
        }
    }

    public int getMaxConcurrentProcessingInstances() {
        /* 2020-01-30: We have to perform a lot of requests --> Set this to 1. */
        return 1;
    }
}
