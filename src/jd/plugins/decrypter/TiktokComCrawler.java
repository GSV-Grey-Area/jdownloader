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

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
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
import jd.plugins.Plugin;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.hoster.TiktokCom;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.DebugMode;
import org.appwork.utils.StringUtils;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.components.config.TiktokConfig;
import org.jdownloader.plugins.components.config.TiktokConfig.CrawlMode;
import org.jdownloader.plugins.components.config.TiktokConfig.DownloadMode;
import org.jdownloader.plugins.components.config.TiktokConfig.ImageFormat;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

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

    private final String TYPE_REDIRECT       = "https?://vm\\.[^/]+/([A-Za-z0-9]+).*";
    private final String TYPE_APP            = "https?://[^/]+/t/([A-Za-z0-9]+).*";
    private final String TYPE_USER_USERNAME  = "https?://[^/]+/@([^\\?/]+).*";
    private final String TYPE_USER_USER_ID   = "https?://[^/]+/share/user/(\\d+).*";
    private final String TYPE_PLAYLIST_TAG   = "https?://[^/]+/tag/([^/]+)";
    private final String TYPE_PLAYLIST_MUSIC = "https?://[^/]+/music/([a-z0-9\\-]+)-(\\d+)";

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final PluginForHost hosterPlugin = this.getNewPluginForHostInstance(this.getHost());
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        if (param.getCryptedUrl().matches(TYPE_REDIRECT) || param.getCryptedUrl().matches(TYPE_APP)) {
            /* Single redirect URLs */
            br.setFollowRedirects(false);
            final String initialURL = param.getCryptedUrl().replaceFirst("http://", "https://");
            String redirect = initialURL;
            int loops = 0;
            do {
                br.getPage(redirect);
                redirect = br.getRedirectLocation();
                if (redirect != null && hosterPlugin.canHandle(redirect)) {
                    break;
                } else if (br.getHttpConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else if (loops >= 5) {
                    logger.info("Redirectloop -> URL must be offline");
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else {
                    loops++;
                }
            } while (true);
            if (redirect == null || !hosterPlugin.canHandle(redirect)) {
                /* E.g. redirect to mainpage */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            logger.info("Old URL: " + initialURL + " | New URL: " + redirect);
            ret.add(createDownloadlink(redirect));
            return ret;
        } else if (hosterPlugin.canHandle(param.getCryptedUrl())) {
            return crawlSingleMedia(param);
        } else if (param.getCryptedUrl().matches(TYPE_USER_USERNAME) || param.getCryptedUrl().matches(TYPE_USER_USER_ID)) {
            return crawlProfile(param);
        } else if (param.getCryptedUrl().matches(TYPE_PLAYLIST_TAG)) {
            return this.crawlPlaylistTag(param);
        } else if (param.getCryptedUrl().matches(TYPE_PLAYLIST_MUSIC)) {
            return this.crawlPlaylistMusic(param);
        } else {
            // unsupported url pattern
            logger.warning("Unsupported URL: " + param.getCryptedUrl());
            return new ArrayList<DownloadLink>(0);
        }
    }

    private ArrayList<DownloadLink> crawlSingleMedia(final CryptedLink param) throws Exception {
        return crawlSingleMedia(param, AccountController.getInstance().getValidAccount(this.getHost()));
    }

    public ArrayList<DownloadLink> crawlSingleMedia(final CryptedLink param, final Account account) throws Exception {
        final boolean useNewHandlingInDevMode = false;
        if (DebugMode.TRUE_IN_IDE_ELSE_FALSE && useNewHandlingInDevMode) {
            /* 2022-08-25: New handling: Under development */
            if (TiktokCom.getDownloadMode() == DownloadMode.WEBSITE) {
                return crawlSingleMediaWebsite(param.getCryptedUrl(), null);
            } else {
                return this.crawlSingleMediaAPI(param.getCryptedUrl(), null);
            }
        } else {
            /* Single video URL --> Is handled by host plugin */
            final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
            ret.add(this.createDownloadlink(param.getCryptedUrl()));
            return ret;
        }
    }

    /** This can crawl single videos from website. */
    @Deprecated
    public ArrayList<DownloadLink> crawlSingleMediaWebsite(final String url, final Account account) throws Exception {
        final TiktokCom hostPlg = (TiktokCom) this.getNewPluginForHostInstance(this.getHost());
        if (account != null) {
            hostPlg.login(account, false);
        }
        /* In website mode we neither know whether or not a video is watermarked nor can we download it without watermark. */
        final String fid = TiktokCom.getContentID(url);
        if (fid == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        prepBRWebsite(br);
        final TiktokConfig cfg = PluginJsonConfig.get(hostPlg.getConfigInterface());
        final DownloadLink video = new DownloadLink(hostPlg, this.getHost(), url);
        String description = null;
        String username = null;
        Object diggCountO = null;
        Object shareCountO = null;
        Object playCountO = null;
        Object commentCountO = null;
        String dateFormatted = null;
        final boolean useWebsiteEmbed = true;
        /**
         * 2021-04-09: Avoid using the website-way as their bot protection may kick in right away! </br> When using an account and
         * potentially downloading private videos however, we can't use the embed way.
         */
        String videoDllink = null;
        if (account != null) {
            br.getPage(url);
            if (this.br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.containsHTML("pageDescKey\\s*=\\s*'user_verify_page_description';|class=\"verify-wrap\"")) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Captcha-blocked");
            }
            String videoJson = br.getRegex("crossorigin=\"anonymous\">\\s*(.*?)\\s*</script>").getMatch(0);
            if (videoJson == null) {
                videoJson = br.getRegex("<script\\s*id[^>]*>\\s*(\\{.*?)\\s*</script>").getMatch(0);
            }
            final Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(videoJson);
            final Map<String, Object> itemModule = (Map<String, Object>) entries.get("ItemModule");
            /* 2020-10-12: Hmm reliably checking for offline is complicated so let's try this instead ... */
            if (itemModule == null || itemModule.isEmpty()) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final Map<String, Object> videoInfo = (Map<String, Object>) itemModule.entrySet().iterator().next().getValue();
            description = (String) entries.get("desc");
            final Map<String, Object> downloadInfo = (Map<String, Object>) videoInfo.get("video");
            videoDllink = (String) downloadInfo.get("downloadAddr");
            if (StringUtils.isEmpty(videoDllink)) {
                videoDllink = (String) downloadInfo.get("playAddr");
            }
            username = videoInfo.get("author").toString();
            final String createDateTimestampStr = videoInfo.get("createTime").toString();
            if (!StringUtils.isEmpty(createDateTimestampStr)) {
                dateFormatted = TiktokCom.convertDateFormat(createDateTimestampStr);
            }
            final Map<String, Object> stats = (Map<String, Object>) videoInfo.get("stats");
            diggCountO = stats.get("diggCount");
            shareCountO = stats.get("shareCount");
            playCountO = stats.get("playCount");
            commentCountO = stats.get("commentCount");
            if (videoDllink == null) {
                /* Fallback */
                videoDllink = TiktokCom.generateDownloadurlOld(br, fid);
                video.setProperty(TiktokCom.PROPERTY_ALLOW_HEAD_REQUEST, true);
            } else {
                video.setProperty(TiktokCom.PROPERTY_ALLOW_HEAD_REQUEST, false);
            }
        } else if (useWebsiteEmbed) {
            /* Old version: https://www.tiktok.com/embed/<videoID> */
            // br.getPage(String.format("https://www.tiktok.com/embed/%s", fid));
            /* Alternative URL: https://www.tiktok.com/node/embed/render/<videoID> */
            /*
             * 2021-04-09: Without accessing their website before (= fetches important cookies), we won't be able to use our final
             * downloadurl!!
             */
            /* 2021-04-09: Both ways will work fine but the oembed one is faster and more elegant. */
            if (account != null) {
                br.getPage(url);
            } else {
                br.getPage("https://www." + this.getHost() + "/oembed?url=" + Encoding.urlEncode("https://www." + this.getHost() + "/video/" + fid));
            }
            if (br.containsHTML("\"(?:status_msg|message)\"\\s*:\\s*\"Something went wrong\"")) {
                // webmode not possible!? retry with api
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            /* Required headers! */
            final Browser brc = this.br.cloneBrowser();
            brc.getHeaders().put("sec-fetch-dest", "iframe");
            brc.getHeaders().put("sec-fetch-mode", "navigate");
            // brc.getHeaders().put("sec-fetch-site", "cross-site");
            // brc.getHeaders().put("upgrade-insecure-requests", "1");
            // brc.getHeaders().put("Referer", link.getPluginPatternMatcher());
            brc.getPage("https://www." + this.getHost() + "/embed/v2/" + fid);
            brc.followRedirect(); // without this we have different videoJson
            if (brc.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (brc.containsHTML("pageDescKey\\s*=\\s*'user_verify_page_description';|class=\"verify-wrap\"")) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Captcha-blocked");
            }
            String videoJson = brc.getRegex("crossorigin=\"anonymous\">\\s*(.*?)\\s*</script>").getMatch(0);
            if (videoJson == null) {
                videoJson = brc.getRegex("<script\\s*id[^>]*>\\s*(\\{.*?)\\s*</script>").getMatch(0);
            }
            final Map<String, Object> root = JavaScriptEngineFactory.jsonToJavaMap(videoJson);
            Map<String, Object> videoData = (Map<String, Object>) JavaScriptEngineFactory.walkJson(root, "props/pageProps/videoData");
            if (videoData == null) {
                // different videoJson when we do not follow the embed/v2 redirect
                Map<String, Object> data = (Map<String, Object>) JavaScriptEngineFactory.walkJson(root, "source/data/");
                if (data != null) {
                    String key = null;
                    for (String keyEntry : data.keySet()) {
                        if (StringUtils.containsIgnoreCase(keyEntry, fid)) {
                            key = keyEntry;
                            break;
                        }
                    }
                    // key contains / separator, so we must use different walkJson here
                    videoData = (Map<String, Object>) JavaScriptEngineFactory.walkJson(root, "source", "data", key, "videoData");
                }
            }
            /* 2020-10-12: Hmm reliably checking for offline is complicated so let's try this instead ... */
            if (videoData == null) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final Map<String, Object> itemInfos = (Map<String, Object>) videoData.get("itemInfos");
            final Map<String, Object> musicInfos = (Map<String, Object>) videoData.get("musicInfos");
            // entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "videoData/itemInfos");
            /* In some cases this will be "0". In these cases, the date will be obtained from "last modified" header via website. */
            final String createTime = itemInfos.get("createTime").toString();
            description = (String) itemInfos.get("text");
            videoDllink = (String) JavaScriptEngineFactory.walkJson(itemInfos, "video/urls/{0}");
            /* Always look for username --> Username given inside URL which user added can be wrong! */
            final Object authorInfosO = videoData.get("authorInfos");
            if (authorInfosO != null) {
                final Map<String, Object> authorInfos = (Map<String, Object>) authorInfosO;
                username = (String) authorInfos.get("uniqueId");
            }
            /* Set more Packagizer properties */
            diggCountO = itemInfos.get("diggCount");
            playCountO = itemInfos.get("playCount");
            shareCountO = itemInfos.get("shareCount");
            commentCountO = itemInfos.get("commentCount");
            if (!StringUtils.isEmpty(createTime) && !"0".equals(createTime)) {
                dateFormatted = TiktokCom.convertDateFormat(createTime);
            }
            if (videoDllink == null) {
                /* Fallback */
                videoDllink = TiktokCom.generateDownloadurlOld(br, fid);
            }
            video.setProperty(TiktokCom.PROPERTY_ALLOW_HEAD_REQUEST, true);
            if (cfg.isVideoCrawlerCrawlAudioSeparately() && musicInfos != null) {
                final List<String> audioURLs = (List<String>) musicInfos.get("playUrl");
                final String audioDirecturl = audioURLs.get(0);
                final DownloadLink audio = new DownloadLink(hostPlg, this.getHost(), url);
                audio.setProperty(TiktokCom.PROPERTY_DIRECTURL_WEBSITE, audioDirecturl);
                audio.setProperty(TiktokCom.PROPERTY_TYPE, TiktokCom.TYPE_AUDIO);
                audio.setProperty(TiktokCom.PROPERTY_ALLOW_HEAD_REQUEST, true);
                ret.add(audio);
            }
        } else {
            /* Rev. 40928 and earlier */
            videoDllink = TiktokCom.generateDownloadurlOld(br, fid);
            video.setProperty(TiktokCom.PROPERTY_ALLOW_HEAD_REQUEST, true);
        }
        if (!StringUtils.isEmpty(videoDllink)) {
            video.setProperty(TiktokCom.PROPERTY_DIRECTURL_WEBSITE, videoDllink);
        }
        video.setProperty(TiktokCom.PROPERTY_TYPE, TiktokCom.TYPE_VIDEO);
        ret.add(video);
        /* Add additional properties */
        for (final DownloadLink result : ret) {
            result.setAvailable(true);
            TiktokCom.setFilename(result);
            TiktokCom.setDescriptionAndHashtags(result, description);
            if (!StringUtils.isEmpty(username)) {
                result.setProperty(TiktokCom.PROPERTY_USERNAME, username);
            }
            if (diggCountO != null) {
                TiktokCom.setLikeCount(result, (Number) diggCountO);
            }
            if (shareCountO != null) {
                TiktokCom.setShareCount(result, (Number) shareCountO);
            }
            if (playCountO != null) {
                TiktokCom.setPlayCount(result, (Number) playCountO);
            }
            if (commentCountO != null) {
                TiktokCom.setCommentCount(result, (Number) commentCountO);
            }
            if (dateFormatted != null) {
                result.setProperty(TiktokCom.PROPERTY_DATE, dateFormatted);
            }
        }
        return ret;
    }

    public ArrayList<DownloadLink> crawlSingleMediaAPI(final String url, final Account account) throws Exception {
        final TiktokCom hostPlg = (TiktokCom) this.getNewPluginForHostInstance(this.getHost());
        if (account != null) {
            hostPlg.login(account, false);
        }
        /* In website mode we neither know whether or not a video is watermarked nor can we download it without watermark. */
        final String fid = TiktokCom.getContentID(url);
        if (fid == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final TiktokConfig cfg = PluginJsonConfig.get(TiktokConfig.class);
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        prepBRAPI(br);
        final UrlQuery query = TiktokCom.getAPIQuery();
        query.add("aweme_id", fid);
        /* Alternative check for videos not available without feed-context: same request with path == '/feed' */
        // accessAPI(br, "/feed", query);
        TiktokCom.accessAPI(br, "/aweme/detail", query);
        final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        final Map<String, Object> aweme_detail = (Map<String, Object>) entries.get("aweme_detail");
        if (aweme_detail == null) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Map<String, Object> status = (Map<String, Object>) aweme_detail.get("status");
        if ((Boolean) status.get("is_delete")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String dateFormatted = new SimpleDateFormat("yyyy-MM-dd").format(new Date(((Number) aweme_detail.get("create_time")).longValue() * 1000));
        final Map<String, Object> statistics = (Map<String, Object>) aweme_detail.get("statistics");
        final Map<String, Object> video = (Map<String, Object>) aweme_detail.get("video");
        final Map<String, Object> music = (Map<String, Object>) aweme_detail.get("music");
        final Map<String, Object> author = (Map<String, Object>) aweme_detail.get("author");
        final Map<String, Object> image_post_info = (Map<String, Object>) aweme_detail.get("image_post_info");
        final boolean crawlAudio;
        if (image_post_info != null) {
            /* Image post */
            final String preferredImageFileExtension;
            if (cfg.getPreferredImageFormat() == ImageFormat.JPEG) {
                preferredImageFileExtension = ".jpeg";
            } else {
                preferredImageFileExtension = ".webp";
            }
            final List<Map<String, Object>> images = (List<Map<String, Object>>) image_post_info.get("images");
            int index = 0;
            for (final Map<String, Object> image : images) {
                final Map<String, Object> imageMap;
                if (cfg.isImageCrawlerCrawlImagesWithoutWatermark()) {
                    imageMap = (Map<String, Object>) image.get("display_image");
                } else {
                    imageMap = (Map<String, Object>) image.get("user_watermark_image");
                }
                final List<String> url_list = (List<String>) imageMap.get("url_list");
                String preferredImageURL = null;
                for (final String image_url : url_list) {
                    final String thisExt = Plugin.getFileNameExtensionFromURL(image_url);
                    if (StringUtils.equalsIgnoreCase(thisExt, preferredImageFileExtension)) {
                        preferredImageURL = image_url;
                        break;
                    }
                }
                final String chosenImageURL;
                if (preferredImageURL == null) {
                    /* Fallback - this should never be required! */
                    chosenImageURL = url_list.get(0);
                    logger.info("Failed to find preferred image format -> Fallback: " + chosenImageURL);
                } else {
                    chosenImageURL = preferredImageURL;
                }
                final DownloadLink picture = new DownloadLink(hostPlg, this.getHost(), url);
                picture.setProperty(TiktokCom.PROPERTY_DIRECTURL_API, chosenImageURL);
                picture.setProperty(TiktokCom.PROPERTY_TYPE, TiktokCom.TYPE_PICTURE);
                picture.setProperty(TiktokCom.PROPERTY_INDEX, index);
                picture.setProperty(TiktokCom.PROPERTY_INDEX_MAX, images.size());
                ret.add(picture);
                index++;
            }
            /* Force crawl audio as audio is part of that "image slideshow" on tiktok website. */
            crawlAudio = true;
        } else {
            /* Video post */
            final DownloadLink video0 = new DownloadLink(hostPlg, this.getHost(), url);
            final Boolean has_watermark = Boolean.TRUE.equals(video.get("has_watermark"));
            Map<String, Object> downloadInfo = (Map<String, Object>) video.get("download_addr");
            if (downloadInfo == null) {
                /* Fallback/old way */
                final String downloadJson = video.get("misc_download_addrs").toString();
                final Map<String, Object> misc_download_addrs = JSonStorage.restoreFromString(downloadJson, TypeRef.HASHMAP);
                downloadInfo = (Map<String, Object>) misc_download_addrs.get("suffix_scene");
            }
            final Map<String, Object> play_addr = (Map<String, Object>) video.get("play_addr");
            final boolean tryHDDownload = false;
            // if (cfg.getDownloadMode() == DownloadMode.API_HD) {
            if (tryHDDownload && DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                /* User prefers to download HD version */
                /*
                 * 2022-08-17: Look like HD versions have been disabled serverside see e.g.:
                 * https://github.com/yt-dlp/yt-dlp/issues/4138#issuecomment-1217380819
                 */
                /**
                 * This is also possible using "https://api-h2.tiktokv.com/aweme/v1/play/" </br> This is also possible using modified URLs
                 * in e.g.: play_addr_bytevc1/uri_list/{last_item} --> Or also any item inside any "uri_list" which contains the "video_id"
                 * parameter which also typically matches play_addr/uri
                 */
                video0.setProperty(TiktokCom.PROPERTY_DIRECTURL_API, String.format("https://api.tiktokv.com/aweme/v1/play/?video_id=%s&line=0&watermark=0&source=AWEME_DETAIL&is_play_url=1&ratio=default&improve_bitrate=1", play_addr.get("uri").toString()));
                /*
                 * This way we can't know whether or not the video comes with watermark but usually this version will not contain a
                 * watermark.
                 */
                video0.removeProperty(null);
                /* We can't know the filesize of this video version in beforehand. */
                video0.setVerifiedFileSize(-1);
            } else {
                /* Get non-HD directurl */
                String directurl = null;
                final Number data_size = downloadInfo != null ? (Number) downloadInfo.get("data_size") : null;
                if (has_watermark || (Boolean.TRUE.equals(aweme_detail.get("prevent_download")) && downloadInfo == null)) {
                    /* Get stream downloadurl because it comes WITHOUT watermark anyways */
                    if (has_watermark) {
                        video0.setProperty(TiktokCom.PROPERTY_HAS_WATERMARK, true);
                    } else {
                        video0.setProperty(TiktokCom.PROPERTY_HAS_WATERMARK, null);
                    }
                    directurl = (String) JavaScriptEngineFactory.walkJson(play_addr, "url_list/{0}");
                    video0.setProperty(TiktokCom.PROPERTY_DIRECTURL_API, directurl);
                    if (data_size != null) {
                        /**
                         * Set filesize of download-version because streaming- and download-version are nearly identical. </br> If a video
                         * is watermarked and downloads are prohibited both versions should be identical.
                         */
                        video0.setDownloadSize(data_size.longValue());
                    }
                } else {
                    /* Get official downloadurl. */
                    final Object directURL = JavaScriptEngineFactory.walkJson(downloadInfo, "url_list/{0}");
                    if (directURL != null) {
                        video0.setProperty(TiktokCom.PROPERTY_DIRECTURL_API, StringUtils.valueOfOrNull(directURL));
                        if (data_size != null) {
                            video0.setVerifiedFileSize(data_size.longValue());
                        }
                        video0.removeProperty(TiktokCom.PROPERTY_HAS_WATERMARK);
                    }
                }
            }
            video0.setProperty(TiktokCom.PROPERTY_TYPE, TiktokCom.TYPE_VIDEO);
            ret.add(video0);
            /* User decides whether or not he wants to download the audio of this video separately. */
            crawlAudio = cfg.isVideoCrawlerCrawlAudioSeparately();
        }
        if (crawlAudio && music != null) {
            final String musicURL = JavaScriptEngineFactory.walkJson(music, "play_url/uri").toString();
            String ext = Plugin.getFileNameExtensionFromURL(musicURL);
            if (ext == null) {
                /* Fallback */
                ext = ".mp3";
            }
            final DownloadLink audio = new DownloadLink(hostPlg, this.getHost(), url);
            audio.setProperty(TiktokCom.PROPERTY_DIRECTURL_API, musicURL);
            audio.setProperty(TiktokCom.PROPERTY_TYPE, TiktokCom.TYPE_AUDIO);
            ret.add(audio);
        }
        /* Set additional properties and find packagename */
        String packagename = null;
        for (final DownloadLink result : ret) {
            result.setAvailable(true);
            result.setProperty(TiktokCom.PROPERTY_DATE, dateFormatted);
            result.setProperty(TiktokCom.PROPERTY_USERNAME, author.get("unique_id"));
            TiktokCom.setDescriptionAndHashtags(result, aweme_detail.get("desc").toString());
            TiktokCom.setLikeCount(result, (Number) statistics.get("digg_count"));
            TiktokCom.setPlayCount(result, (Number) statistics.get("play_count"));
            TiktokCom.setShareCount(result, (Number) statistics.get("share_count"));
            TiktokCom.setCommentCount(result, (Number) statistics.get("comment_count"));
            result.setProperty(TiktokCom.PROPERTY_ALLOW_HEAD_REQUEST, true);
            TiktokCom.setFilename(result);
            if (packagename == null && (result.getStringProperty(TiktokCom.PROPERTY_TYPE).equals(TiktokCom.TYPE_AUDIO) || result.getStringProperty(TiktokCom.PROPERTY_TYPE).equals(TiktokCom.TYPE_VIDEO))) {
                final String filename = result.getName();
                packagename = filename.substring(0, filename.lastIndexOf("."));
            }
        }
        if (packagename != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(packagename);
            fp.setCleanupPackageName(false);
            fp.addLinks(ret);
        }
        return ret;
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
     * Use website to crawl all videos of a user. </br> Pagination hasn't been implemented so this will only find the first batch of items -
     * usually around 30 items!
     */
    public ArrayList<DownloadLink> crawlProfileWebsite(final CryptedLink param) throws Exception {
        prepBRWebsite(br);
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
            final UrlQuery query = TiktokCom.getWebsiteQuery();
            query.add("keyword", Encoding.urlEncode(usernameSlug));
            br.getPage("https://www." + this.getHost() + "/api/search/general/preview/?" + query.toString());
            sleep(1000, param);// this somehow bypass the protection, maybe calling api twice sets a cookie?
            br.getPage("https://www." + this.getHost() + "/api/search/general/preview/?" + query.toString());
            br.getPage(param.getCryptedUrl());
        }
        this.botProtectionCheck(br);
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
                dummy.setFinalFileName("CANNOT_CRAWL_MORE_THAN_" + videos.size() + "_ITEMS_OF_PROFILE_" + usernameSlug + "_IN_WEBSITE_PROFILE_CRAWL_MODE");
                dummy.setComment("This crawler plugin cannot handle pagination in website mode thus it is currently impossible to crawl more than " + videos.size() + " items of this particular profile. Check this forum thread for more info: https://board.jdownloader.org/showthread.php?t=79982");
                if (fp != null) {
                    dummy._setFilePackage(fp);
                }
                distribute(dummy);
                ret.add(dummy);
            }
        } catch (final Exception ignore) {
            logger.log(ignore);
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
        String user_id = null;
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
            final List<Map<String, Object>> sug_list = (List<Map<String, Object>>) JavaScriptEngineFactory.walkJson(searchResults, "sug_list/");
            for (Map<String, Object> sug : sug_list) {
                final Map<String, Object> info = (Map<String, Object>) sug.get("extra_info");
                final String sug_uniq_id = info != null ? StringUtils.valueOfOrNull(info.get("sug_uniq_id")) : null;
                if (StringUtils.equals(usernameSlug, sug_uniq_id)) {
                    user_id = info.get("sug_user_id").toString();
                    break;
                }
            }
            if (user_id == null) {
                logger.info("Using fallback method to find userID!");
                website.getPage(param.getCryptedUrl());
                user_id = website.getRegex("\"authorId\"\\s*:\\s*\"(.*?)\"").getMatch(0);
                if (user_id == null && TiktokCom.isBotProtectionActive(website)) {
                    sleep(1000, param);// this somehow bypass the protection, maybe calling api twice sets a cookie?
                    website.getPage("https://www." + this.getHost() + "/api/search/general/preview/?" + query.toString());
                    website.getPage(param.getCryptedUrl());
                    user_id = website.getRegex("\"authorId\"\\s*:\\s*\"(.*?)\"").getMatch(0);
                }
            }
            if (user_id == null) {
                logger.info("Profile doesn't exist or it's a private profile");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        }
        prepBRAPI(this.br);
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
                final DownloadLink link = processVideo(aweme_detail);
                if (fp == null) {
                    /*
                     * Collect author name on first round because it is not always given before e.g. not given if user adds URL of type
                     * TYPE_USER_USER_ID.
                     */
                    author = link.getStringProperty(TiktokCom.PROPERTY_USERNAME);
                    fp = getFilePackage(author);
                }
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

    public ArrayList<DownloadLink> crawlPlaylistTag(final CryptedLink param) throws Exception {
        if (PluginJsonConfig.get(TiktokConfig.class).getTagCrawlerMaxItemsLimit() == 0) {
            logger.info("User has disabled tag crawler --> Returning empty array");
            return new ArrayList<DownloadLink>();
        }
        return crawlPlaylistAPI(param);
    }

    public ArrayList<DownloadLink> crawlPlaylistAPI(final CryptedLink param) throws Exception {
        final String tagName = new Regex(param.getCryptedUrl(), TYPE_PLAYLIST_TAG).getMatch(0);
        if (tagName == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        prepBRWebsite(br);
        br.getPage(param.getCryptedUrl());
        botProtectionCheck(br);
        final String tagID = br.getRegex("snssdk\\d+://challenge/detail/(\\d+)").getMatch(0);
        if (tagID == null) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName("tag - " + tagName);
        return crawlPlaylistAPI("/challenge/aweme", "ch_id", tagID, fp);
    }

    /** Under development */
    public ArrayList<DownloadLink> crawlPlaylistMusic(final CryptedLink param) throws Exception {
        // TODO
        if (!DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // if (PluginJsonConfig.get(TiktokConfig.class).getTagCrawlerMaxItemsLimit() == 0) {
        // logger.info("User has disabled tag crawler --> Returning empty array");
        // return new ArrayList<DownloadLink>();
        // }
        return crawlPlaylistMusicAPI(param);
    }

    /** Under development */
    public ArrayList<DownloadLink> crawlPlaylistMusicAPI(final CryptedLink param) throws Exception {
        // TODO
        if (!DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final Regex urlinfo = new Regex(param.getCryptedUrl(), TYPE_PLAYLIST_MUSIC);
        final String musicPlaylistTitle = urlinfo.getMatch(0);
        final String musicID = urlinfo.getMatch(1);
        if (musicPlaylistTitle == null || musicID == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName("music - " + musicPlaylistTitle);
        return crawlPlaylistAPI("/music/aweme", "music_id", musicID, fp);
    }

    /** Generic function to crawl playlist-like stuff. */
    public ArrayList<DownloadLink> crawlPlaylistAPI(final String apiPath, final String playlistKeyName, final String playlistID, final FilePackage fp) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        logger.info("Crawling playlist or playlist-like item with ID: " + playlistID);
        final int maxItemsPerPage = 20;
        final UrlQuery query = TiktokCom.getAPIQuery();
        query.add(playlistKeyName, playlistID);
        query.add("cursor", "0");
        query.add("count", Integer.toString(maxItemsPerPage));
        query.add("type", "5");
        query.add("device_id", generateDeviceID());
        prepBRAPI(this.br);
        final TiktokConfig cfg = PluginJsonConfig.get(TiktokConfig.class);
        int page = 1;
        do {
            TiktokCom.accessAPI(br, apiPath, query);
            final Map<String, Object> entries = JSonStorage.restoreFromString(br.getRequest().getHtmlCode(), TypeRef.HASHMAP);
            final List<Map<String, Object>> videos = (List<Map<String, Object>>) entries.get("aweme_list");
            if (videos.isEmpty()) {
                if (ret.isEmpty()) {
                    /* There are no videos with this tag available. */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else {
                    /* This should never happen! */
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            for (final Map<String, Object> aweme_detail : videos) {
                final DownloadLink link = this.processVideo(aweme_detail);
                if (fp != null) {
                    link._setFilePackage(fp);
                }
                ret.add(link);
                distribute(link);
                if (ret.size() == cfg.getTagCrawlerMaxItemsLimit()) {
                    logger.info("Stopping because: Reached user defined max items limit: " + cfg.getTagCrawlerMaxItemsLimit());
                    return ret;
                }
            }
            logger.info("Crawled page " + page + "Number of items on current page " + videos.size() + " | Found items so far: " + ret.size());
            if (this.isAbort()) {
                logger.info("Stopping because: Aborted by user");
                break;
            } else if (((Integer) entries.get("has_more")).intValue() != 1) {
                logger.info("Stopping because: Reached end");
                break;
            }
            final String nextCursor = entries.get("cursor").toString();
            if (StringUtils.isEmpty(nextCursor)) {
                /* Additional fail-safe */
                logger.info("Stopping because: Failed to find cursor --> Reached end?");
                break;
            } else {
                query.addAndReplace("cursor", nextCursor);
                page++;
            }
        } while (true);
        return ret;
    }

    private ArrayList<DownloadLink> processVideoList(final List<Map<String, Object>> videos, final FilePackage fp) throws PluginException {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        for (final Map<String, Object> aweme_detail : videos) {
            final DownloadLink link = processVideo(aweme_detail);
            link._setFilePackage(fp);
            ret.add(link);
            distribute(link);
        }
        return ret;
    }

    private DownloadLink processVideo(final Map<String, Object> aweme_detail) throws PluginException {
        final Map<String, Object> author = (Map<String, Object>) aweme_detail.get("author");
        final DownloadLink link = this.createDownloadlink(getContentURL(author.get("unique_id").toString(), aweme_detail.get("aweme_id").toString()));
        TiktokCom.parseFileInfoAPI(this, link, aweme_detail);
        return link;
    }

    /* Throws exception if bot protection is active according to given browser instances' html code. */
    private void botProtectionCheck(final Browser br) throws DecrypterRetryException {
        if (TiktokCom.isBotProtectionActive(br)) {
            throw new DecrypterRetryException(RetryReason.CAPTCHA, "Bot protection active, cannot crawl any items", null, null);
        }
    }

    private FilePackage getFilePackage(final String name) {
        final FilePackage fp = FilePackage.getInstance();
        fp.setCleanupPackageName(false);
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

    /** Wrapper */
    private Browser prepBRWebsite(final Browser br) {
        return TiktokCom.prepBRWebsite(br);
    }

    /** Wrapper */
    private Browser prepBRWebAPI(final Browser br) {
        return TiktokCom.prepBRWebAPI(br);
    }

    /** Wrapper */
    private Browser prepBRAPI(final Browser br) {
        return TiktokCom.prepBRAPI(br);
    }
}
