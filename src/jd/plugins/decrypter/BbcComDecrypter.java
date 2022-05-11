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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.hoster.BbcCom;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "bbc.com" }, urls = { "https?://(?:www\\.)?(?:bbc\\.com|bbc\\.co\\.uk)/.+" })
public class BbcComDecrypter extends PluginForDecrypt {
    public BbcComDecrypter(PluginWrapper wrapper) {
        super(wrapper);
    }

    private final String        TYPE_EMBED      = "https?://[^/]+/news/av-embeds/(\\d+)";
    private static final String TYPE_PROGRAMMES = "https?://[^/]+/programmes/([^/]+)$";

    @SuppressWarnings("unchecked")
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("This programme is not currently available on BBC iPlayer")) {
            /* Content is online but not streamable at the moment */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (br.getURL().matches(TYPE_EMBED)) {
            return this.crawlEmbed(param);
        }
        String url_name = null;
        if (decryptedLinks.size() == 0 && param.getCryptedUrl().matches(".+/video/[^/]+/.+")) {
            url_name = new Regex(param.getCryptedUrl(), "/video/[^/]+/(.+)").getMatch(0);
        }
        String fpName = br.getRegex("<title>([^<>\"]*?)</title>").getMatch(0);
        String[] jsons = this.br.getRegex("data\\-playable=\"(.*?)\">").getColumn(0);
        if (jsons != null && jsons.length != 0) {
            jsons[0] = Encoding.htmlDecode(jsons[0]);
        }
        if (jsons == null || jsons.length == 0) {
            /* Type 1 */
            jsons = this.br.getRegex("data\\-playable=\\'(.*?)\\'>").getColumn(0);
        }
        if (jsons == null || jsons.length == 0) {
            /* Type 2 */
            jsons = this.br.getRegex("playlistObject\\s*?:\\s*?(\\{.*?\\}),[\n]+").getColumn(0);
        }
        if (jsons == null || jsons.length == 0) {
            /* Type 3 */
            jsons = this.br.getRegex("_exposedData=(\\{.+),").getColumn(0);
        }
        if (jsons == null || jsons.length == 0) {
            /* Type 4 OR 5 */
            jsons = this.br.getRegex("mediator\\.bind\\((\\{.*?\\}),\\s*?document\\.").getColumn(0);
            if (jsons == null || jsons.length == 0) {
                /* 2017-12-05 */
                jsons = this.br.getRegex("\\(\"tviplayer\"\\),(\\{.*?\\})\\);").getColumn(0);
            }
        }
        if (jsons == null || jsons.length == 0) {
            /* Type 6 */
            /* 2018-11-15 */
            jsons = this.br.getRegex("window\\.mediatorDefer=page\\(document\\.getElementById\\(\"tviplayer\"\\),(\\{.*?\\}\\}\\})\\);").getColumn(0);
        }
        if (jsons == null || jsons.length == 0) {
            /* Type 7 - Radio */
            /* 2018-11-15 */
            jsons = this.br.getRegex("window\\.__PRELOADED_STATE__ = (\\{.*?\\});").getColumn(0);
        }
        if (jsons == null || jsons.length == 0) {
            /* Type 8 */
            /* 2018-12-07 */
            jsons = this.br.getRegex("<script id=\"initial\\-data\" type=\"text/plain\" data\\-json=\\'([^<>\"\\']+)\\'").getColumn(0);
        }
        if (jsons == null || jsons.length == 0) {
            /* Type 9 (similar to 5) */
            /* 2019-04-04 */
            jsons = this.br.getRegex("window\\.__IPLAYER_REDUX_STATE__ = (\\{.*?\\});").getColumn(0);
        }
        if (jsons == null || jsons.length == 0) {
            /* 2021-08-05: bbc.co.uk/archive/.* */
            jsons = this.br.getRegex("(\\{\"meta\".*?\\})\\);\\s*\\}\\);</script>").getColumn(0);
        }
        if (jsons == null) {
            logger.info("Failed to find any playable content");
            return decryptedLinks;
        }
        Map<String, Object> entries = null;
        Map<String, Object> entries2 = null;
        for (String json : jsons) {
            if (json.contains("{&quot;")) {
                json = Encoding.htmlDecode(json);
            }
            try {
                entries = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(json);
            } catch (final Throwable ignore) {
                logger.info("Unsupported json (parser failure): " + json);
                continue;
            }
            final Object o_story = entries.get("story");
            final Object o_player = entries.get("player");
            final Object o_episode = entries.get("episode");
            final Object o_versions = entries.get("versions");
            final Object o_appStoreState = entries.get("appStoreState");
            final Object o_programmes = entries.get("programmes");
            final Object o_body_video = JavaScriptEngineFactory.walkJson(entries, "body/video");
            String title = null;
            String subtitle = null;
            String description = null;
            String tv_brand = null;
            String episodeType = null;
            String date = null;
            String date_formatted = null;
            String vpid = null;
            if (o_story != null) {
                /* Type 3 */
                entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "story/Content/AssetVideoIb2/{0}");
                if (entries == null) {
                    logger.info("Failed to find video content");
                    break;
                }
                title = (String) entries.get("Title");
                vpid = (String) entries.get("Vpid");
            } else if (o_player != null && ((Map<String, Object>) o_player).containsKey("title")) {
                /* Type 4 */
                entries2 = (Map<String, Object>) o_episode;
                entries = (Map<String, Object>) o_player;
                title = (String) entries.get("title");
                subtitle = (String) entries.get("subtitle");
                vpid = (String) entries.get("vpid");
                tv_brand = (String) entries.get("masterbrand");
                episodeType = (String) entries.get("episodeType");
                date = (String) entries2.get("release_date_time");
                description = (String) JavaScriptEngineFactory.walkJson(entries, "synopses/large");
            } else if (o_episode != null && o_versions != null) {
                /* Type 9 - similar to type 5 */
                entries = (Map<String, Object>) o_episode;
                title = (String) entries.get("title");
                subtitle = (String) entries.get("subtitle");
                vpid = (String) JavaScriptEngineFactory.walkJson(o_versions, "{0}/id");
                tv_brand = (String) JavaScriptEngineFactory.walkJson(entries, "master_brand/id");
                episodeType = (String) entries.get("type");
                date = (String) entries.get("release_date_time");
                description = (String) JavaScriptEngineFactory.walkJson(entries, "synopses/large");
            } else if (o_episode != null) {
                /* Type 5 */
                entries = (Map<String, Object>) o_episode;
                title = (String) entries.get("title");
                subtitle = (String) entries.get("subtitle");
                vpid = (String) JavaScriptEngineFactory.walkJson(entries, "versions/{0}/id");
                tv_brand = (String) JavaScriptEngineFactory.walkJson(entries, "master_brand/id");
                episodeType = (String) entries.get("type");
                date = (String) entries.get("release_date_time");
                description = (String) JavaScriptEngineFactory.walkJson(entries, "synopses/large");
            } else if (o_appStoreState != null) {
                /* Type 6 */
                entries = (Map<String, Object>) o_appStoreState;
                vpid = (String) JavaScriptEngineFactory.walkJson(entries, "versions/{0}/id");
                date = (String) JavaScriptEngineFactory.walkJson(entries, "versions/{0}/firstBroadcast");
                entries = (Map<String, Object>) entries.get("episode");
                title = (String) entries.get("title");
                subtitle = (String) entries.get("subtitle");
                tv_brand = (String) JavaScriptEngineFactory.walkJson(entries, "masterBrand/id");
                episodeType = (String) entries.get("type");
                // date = (String) entries.get("release_date_time");
                description = (String) JavaScriptEngineFactory.walkJson(entries, "synopses/large");
            } else if (o_programmes != null) {
                /* Type 7 - Audio */
                entries = (Map<String, Object>) o_programmes;
                entries = (Map<String, Object>) entries.get("current");
                vpid = (String) entries.get("id");
                title = (String) JavaScriptEngineFactory.walkJson(entries, "titles/primary");
                description = (String) JavaScriptEngineFactory.walkJson(entries, "titles/secondary");
                tv_brand = (String) JavaScriptEngineFactory.walkJson(entries, "network/id");
                date = (String) JavaScriptEngineFactory.walkJson(entries, "availability/from");
            } else if (entries.containsKey("initData")) {
                /* Type 8 */
                entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "initData/items/{0}/smpData/items/{0}");
                vpid = (String) entries.get("versionID");
            } else if (o_body_video != null) {
                /* 2021-08-05: bbc.co.uk/archive/.* */
                entries = (Map<String, Object>) o_body_video;
                vpid = (String) entries.get("vpid");
                title = (String) entries.get("title");
            } else {
                /* Hopefully type 1 */
                Object sourcemapo = JavaScriptEngineFactory.walkJson(entries, "settings/playlistObject");
                if (sourcemapo == null) {
                    /* Type 2 */
                    sourcemapo = JavaScriptEngineFactory.walkJson(entries, "allAvailableVersions/{0}/smpConfig");
                }
                if (sourcemapo == null) {
                    logger.info("Incompatible json: " + json);
                    continue;
                }
                entries = (Map<String, Object>) sourcemapo;
                title = (String) entries.get("title");
                description = (String) entries.get("summary");
                entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "items/{0}");
                vpid = (String) entries.get("vpid");
            }
            if (StringUtils.isEmpty(title)) {
                /* Last chance fallback */
                title = url_name;
            }
            if (StringUtils.isEmpty(vpid)) {
                continue;
            }
            final DownloadLink dl = generateDownloadlink(vpid);
            if (!StringUtils.isEmpty(title)) {
                dl.setProperty(BbcCom.PROPERTY_TITLE, title);
                dl.setProperty(BbcCom.PROPERTY_TV_BRAND, tv_brand);
                date_formatted = formatDate(date);
                if (date_formatted != null) {
                    dl.setProperty(BbcCom.PROPERTY_DATE, date_formatted);
                }
                dl.setName(BbcCom.getFilename(dl));
            }
            dl.setContentUrl(param.getCryptedUrl());
            if (!StringUtils.isEmpty(description)) {
                dl.setComment(description);
            }
            decryptedLinks.add(dl);
        }
        /* 2022-01-17: New handling */
        final String jsonMorphSingle = br.getRegex("Morph\\.setPayload\\('[^\\']+', (\\{.*?\\})\\);").getMatch(0);
        if (jsonMorphSingle != null) {
            final Map<String, Object> root = JSonStorage.restoreFromString(jsonMorphSingle, TypeRef.HASHMAP);
            final Map<String, Object> body = (Map<String, Object>) root.get("body");
            fpName = (String) body.get("pageTitle");
            final List<Map<String, Object>> videos = (List<Map<String, Object>>) body.get("videos");
            for (final Map<String, Object> video : videos) {
                final String vpid = (String) video.get("versionPid");
                if (StringUtils.isEmpty(vpid)) {
                    continue;
                }
                final String title = (String) video.get("title");
                final String description = (String) video.get("description");
                final String tv_brand = (String) video.get("masterBrand");
                final String date = (String) video.get("createdDateTime");
                final DownloadLink dl = generateDownloadlink(vpid);
                if (!StringUtils.isEmpty(title)) {
                    dl.setProperty(BbcCom.PROPERTY_TITLE, title);
                    dl.setProperty(BbcCom.PROPERTY_DATE, new Regex(date, "(\\d{4}-\\d{2}-\\d{2})").getMatch(0));
                    dl.setProperty(BbcCom.PROPERTY_TV_BRAND, tv_brand);
                    dl.setContentUrl(param.getCryptedUrl());
                    if (!StringUtils.isEmpty(description)) {
                        dl.setComment(description);
                    }
                    dl.setAvailable(true);
                    dl.setName(BbcCom.getFilename(dl));
                    decryptedLinks.add(dl);
                }
            }
        }
        /* 2022-03-04 e.g. https://www.bbc.com/news/av/world-europe-60608706 */
        final String json2022 = br.getRegex("window\\.__INITIAL_DATA__=\"(.*?\\})\"").getMatch(0);
        if (json2022 != null) {
            final Object videoO = findVideoMap(JavaScriptEngineFactory.jsonToJavaObject(PluginJSonUtils.unescape(json2022)));
            if (videoO != null) {
                final Map<String, Object> video = (Map<String, Object>) videoO;
                final Map<String, Object> structuredData = (Map<String, Object>) video.get("structuredData");
                final String description = (String) structuredData.get("description");
                final String dateFormatted = new Regex(structuredData.get("uploadDate").toString(), "^(\\d{4}-\\d{2}-\\d{2})").getMatch(0);
                final String embedUrl = structuredData.get("embedUrl").toString();
                final Map<String, Object> videoInfo = (Map<String, Object>) JavaScriptEngineFactory.walkJson(video, "mediaItem/media/items/{0}");
                final String vpid = videoInfo.get("id").toString();
                final String title = videoInfo.get("title").toString();
                final DownloadLink dl = this.generateDownloadlink(vpid);
                if (!StringUtils.isEmpty(embedUrl)) {
                    dl.setContentUrl(embedUrl);
                } else {
                    dl.setContentUrl(br.getURL());
                }
                if (!StringUtils.isEmpty(description)) {
                    dl.setComment(description);
                }
                dl.setProperty(BbcCom.PROPERTY_DATE, dateFormatted);
                dl.setProperty(BbcCom.PROPERTY_TITLE, title);
                dl.setAvailable(true);
                dl.setName(BbcCom.getFilename(dl));
                decryptedLinks.add(dl);
            }
        }
        // final String[] newsVpids = br.getRegex("version_offset:(p[a-z0-9]+)").getColumn(0);
        // for (final String newsVpid : newsVpids) {
        // final DownloadLink dl = generateDownloadlink(newsVpid);
        // dl.setContentUrl(parameter);
        // decryptedLinks.add(dl);
        // }
        // final String jsonMorphMultiple = br.getRegex("Morph\\.setPayload\\('[^\\']+', (\\{.*?\\})\\);").getMatch(0);
        if (this.br.getURL().matches(TYPE_PROGRAMMES)) {
            if (decryptedLinks.isEmpty()) {
                decryptedLinks.addAll(crawlProgrammes(param));
            }
        } else {
            if (decryptedLinks.isEmpty()) {
                /* E.g. bbc.co.uk/programmes/blabla/clips --> Look for clips */
                decryptedLinks.addAll(lookForProgrammesURLs(param));
            }
        }
        if (decryptedLinks.size() == 0) {
            logger.info("Failed to find any playable content --> Probably only irrelevant photo content or no content at all --> Adding offline url");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (fpName != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            fp.addLinks(decryptedLinks);
        }
        return decryptedLinks;
    }

    /** Recursive function to find specific map in json */
    private Object findVideoMap(final Object o) {
        if (o instanceof Map) {
            final Map<String, Object> entrymap = (Map<String, Object>) o;
            for (final Map.Entry<String, Object> entry : entrymap.entrySet()) {
                final String key = entry.getKey();
                final Object value = entry.getValue();
                if (key.equals("initialItem") && value instanceof Map) {
                    return value;
                } else if (value instanceof List || value instanceof Map) {
                    final Object hit = findVideoMap(value);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            return null;
        } else if (o instanceof List) {
            final List<Object> array = (List) o;
            for (final Object arrayo : array) {
                if (arrayo instanceof List || arrayo instanceof Map) {
                    final Object pico = findVideoMap(arrayo);
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

    private ArrayList<DownloadLink> lookForProgrammesURLs(final CryptedLink param) throws PluginException {
        if (new Regex(param.getCryptedUrl(), TYPE_PROGRAMMES).matches()) {
            /* Developer mistake! */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        /* These ones will go back into this crawler */
        final String[] urls = br.getRegex("\"(https?://[^/]+/programmes/[a-z0-9]+)\"").getColumn(0);
        for (final String url : urls) {
            ret.add(this.createDownloadlink(url));
        }
        return ret;
    }

    /** Crawls single 'programmes' clips. */
    private ArrayList<DownloadLink> crawlProgrammes(final CryptedLink param) throws PluginException {
        final Regex urlInfo = new Regex(param.getCryptedUrl(), TYPE_PROGRAMMES);
        if (!urlInfo.matches()) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String contentID = urlInfo.getMatch(0);
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String[] jsons = br.getRegex("(\\{\"container\":\"#playout-" + contentID + ".*?\\})\\);").getColumn(0);
        final String date = br.getRegex("class=\"details__streamablefrom\" datetime=\"(\\d{4}-\\d{2}-\\d{2})").getMatch(0);
        if (jsons.length > 0) {
            String playlistTitle = null;
            for (final String json : jsons) {
                final Map<String, Object> root = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
                final Map<String, Object> smpSettings = (Map<String, Object>) root.get("smpSettings");
                final Map<String, Object> playlistObject = (Map<String, Object>) smpSettings.get("playlistObject");
                playlistTitle = (String) playlistObject.get("title");
                final List<Map<String, Object>> videos = (List<Map<String, Object>>) playlistObject.get("items");
                for (final Map<String, Object> video : videos) {
                    final String vpid = (String) video.get("vpid");
                    if (StringUtils.isEmpty(vpid)) {
                        continue;
                    }
                    final DownloadLink link = this.generateDownloadlink(vpid);
                    ret.add(link);
                }
            }
            if (ret.size() == 1) {
                /* We got only 1 result --> Set metadata on it */
                for (final DownloadLink link : ret) {
                    if (date != null) {
                        link.setProperty(BbcCom.PROPERTY_DATE, date);
                    }
                    if (playlistTitle != null) {
                        link.setProperty(BbcCom.PROPERTY_TITLE, playlistTitle);
                    }
                }
            }
        }
        if (ret.isEmpty()) {
            /* Old fallback from 2017 */
            final String[] videoIDs = this.br.getRegex("episode_id=([pbm][a-z0-9]{7})").getColumn(0);
            for (final String vpid : videoIDs) {
                ret.add(createDownloadlink(String.format("http://www.bbc.co.uk/iplayer/episode/%s", vpid)));
            }
        }
        return ret;
    }

    /**
     * Crawls URLs like: https://www.bbc.com/news/av-embeds/60608706 </br>
     * Be sure to access them in beforehand using the global Browser instance.
     */
    private ArrayList<DownloadLink> crawlEmbed(final CryptedLink param) throws PluginException {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String title = br.getRegex("title:\"([^\"]+)\"").getMatch(0);
        final String vpid = br.getRegex("versionID:\"([a-z0-9]+)\"").getMatch(0);
        if (vpid != null) {
            final DownloadLink video = this.generateDownloadlink(vpid);
            video.setContentUrl(br.getURL());
            if (title != null) {
                video.setProperty(BbcCom.PROPERTY_TITLE, Encoding.htmlDecode(title).trim());
            }
            video.setName(BbcCom.getFilename(video));
            video.setAvailable(true);
            ret.add(video);
        }
        return ret;
    }

    private DownloadLink generateDownloadlink(final String videoid) {
        final DownloadLink dl = createDownloadlink("http://bbcdecrypted/" + videoid);
        dl.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        dl.setLinkID(videoid);
        return dl;
    }

    public static String formatDate(final String input) {
        if (input == null) {
            return null;
        }
        String dateformat = null;
        if (input.matches("\\d{4}\\-\\d{2}\\-\\d{2}")) {
            dateformat = "yyyy-MM-dd";
        } else if (input.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
            dateformat = "dd/MM/yyyy";
        } else if (input.matches("\\d{1,2} [A-Z][a-z]+ \\d{4}")) {
            dateformat = "dd MMM yyyy";
        }
        if (dateformat == null) {
            return input;
        }
        final long date = TimeFormatter.getMilliSeconds(input, dateformat, Locale.ENGLISH);
        String formattedDate = null;
        final String targetFormat = "yyyy-MM-dd";
        Date theDate = new Date(date);
        try {
            final SimpleDateFormat formatter = new SimpleDateFormat(targetFormat);
            formattedDate = formatter.format(theDate);
        } catch (Exception e) {
            /* prevent input error killing plugin */
            formattedDate = input;
        }
        return formattedDate;
    }
}
