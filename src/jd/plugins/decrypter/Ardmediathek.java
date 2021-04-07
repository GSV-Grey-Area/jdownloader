//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
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
package jd.plugins.decrypter;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.Hash;
import org.appwork.utils.StringUtils;
import org.appwork.utils.UniqueAlltimeID;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.plugins.components.config.ArdConfigInterface;
import org.jdownloader.plugins.components.config.CheckeinsDeConfig;
import org.jdownloader.plugins.components.config.DasersteConfig;
import org.jdownloader.plugins.components.config.EurovisionConfig;
import org.jdownloader.plugins.components.config.KikaDeConfig;
import org.jdownloader.plugins.components.config.MdrDeConfig;
import org.jdownloader.plugins.components.config.MediathekDasersteConfig;
import org.jdownloader.plugins.components.config.MediathekProperties;
import org.jdownloader.plugins.components.config.MediathekRbbOnlineConfig;
import org.jdownloader.plugins.components.config.NdrDeConfig;
import org.jdownloader.plugins.components.config.OneARDConfig;
import org.jdownloader.plugins.components.config.RbbOnlineConfig;
import org.jdownloader.plugins.components.config.SandmannDeConfig;
import org.jdownloader.plugins.components.config.SportschauConfig;
import org.jdownloader.plugins.components.config.SputnikDeConfig;
import org.jdownloader.plugins.components.config.SrOnlineConfig;
import org.jdownloader.plugins.components.config.WDRConfig;
import org.jdownloader.plugins.components.config.WDRMausConfig;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.MediathekHelper;
import jd.plugins.components.PluginJSonUtils;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "ardmediathek.de", "mediathek.daserste.de", "daserste.de", "rbb-online.de", "sandmann.de", "wdr.de", "sportschau.de", "one.ard.de", "wdrmaus.de", "sr-online.de", "ndr.de", "kika.de", "eurovision.de", "sputnik.de", "mdr.de", "checkeins.de" }, urls = { "https?://(?:[A-Z0-9]+\\.)?ardmediathek\\.de/.+", "https?://(?:www\\.)?mediathek\\.daserste\\.de/.*?documentId=\\d+[^/]*?", "https?://www\\.daserste\\.de/[^<>\"]+/(?:videos|videosextern)/[a-z0-9\\-]+\\.html", "https?://(?:www\\.)?mediathek\\.rbb\\-online\\.de/tv/[^<>\"]+documentId=\\d+[^/]*?", "https?://(?:www\\.)?sandmann\\.de/.+", "https?://(?:[a-z0-9]+\\.)?wdr\\.de/[^<>\"]+\\.html|https?://deviceids-[a-z0-9\\-]+\\.wdr\\.de/ondemand/\\d+/\\d+\\.js", "https?://(?:www\\.)?sportschau\\.de/.*?\\.html",
        "https?://(?:www\\.)?one\\.ard\\.de/tv/[^<>\"]+documentId=\\d+[^/]*?", "https?://(?:www\\.)?wdrmaus\\.de/.+", "https?://sr\\-mediathek\\.sr\\-online\\.de/index\\.php\\?seite=\\d+\\&id=\\d+", "https?://(?:[a-z0-9]+\\.)?ndr\\.de/.*?\\.html", "https?://(?:www\\.)?kika\\.de/[^<>\"]+\\.html", "https?://(?:www\\.)?eurovision\\.de/[^<>\"]+\\.html", "https?://(?:www\\.)?sputnik\\.de/[^<>\"]+\\.html", "https?://(?:www\\.)?mdr\\.de/[^<>\"]+\\.html", "https?://(?:www\\.)?checkeins\\.de/[^<>\"]+\\.html" })
public class Ardmediathek extends PluginForDecrypt {
    private static final String                 EXCEPTION_LINKOFFLINE                      = "EXCEPTION_LINKOFFLINE";
    private static final String                 EXCEPTION_GEOBLOCKED                       = "EXCEPTION_GEOBLOCKED";
    /* Constants */
    private static final String                 type_unsupported                           = ".+ardmediathek\\.de/(tv/live\\?kanal=\\d+|dossiers/.*)";
    private static final String                 type_invalid                               = ".+(ardmediathek|mediathek\\.daserste)\\.de/(download|livestream).+";
    private static final String                 type_embedded                              = "https?://deviceids-[a-z0-9\\-]+\\.wdr\\.de/ondemand/\\d+/\\d+\\.js";
    /* Variables */
    private final HashMap<String, DownloadLink> foundQualitiesMap                          = new HashMap<String, DownloadLink>();
    private final HashMap<String, DownloadLink> foundQualitiesMap_http_urls_via_HLS_master = new HashMap<String, DownloadLink>();
    ArrayList<DownloadLink>                     decryptedLinks                             = new ArrayList<DownloadLink>();
    /* Important: Keep this updated & keep this in order: Highest --> Lowest */
    private final List<String>                  all_known_qualities                        = Arrays.asList("http_6666000_1080", "hls_6666000_1080", "http_3773000_720", "hls_3773000_720", "http_1989000_540", "hls_1989000_540", "http_1213000_360", "hls_1213000_360", "http_605000_280", "hls_605000_280", "http_448000_270", "hls_448000_270", "http_317000_270", "hls_317000_270", "http_189000_180", "hls_189000_180", "http_0_0");
    private final Map<String, Long>             heigth_to_bitrate                          = new HashMap<String, Long>();
    private String                              packagename                                = null;
    {
        heigth_to_bitrate.put("180", 189000l);
        /* keep in mind that sometimes there are two versions for 270! This is the higher one (default)! */
        heigth_to_bitrate.put("270", 448000l);
        heigth_to_bitrate.put("280", 605000l);
        heigth_to_bitrate.put("360", 1213000l);
        heigth_to_bitrate.put("540", 1989000l);
        heigth_to_bitrate.put("576", 1728000l);
        heigth_to_bitrate.put("720", 3773000l);
        heigth_to_bitrate.put("1080", 6666000l);
    }
    private String             subtitleLink   = null;
    private String             parameter      = null;
    private String             title          = null;
    private String             show           = null;
    private String             provider       = null;
    private long               date_timestamp = -1;
    private boolean            grabHLS        = false;
    private String             contentID      = null;
    private ArdConfigInterface cfg            = null;

    public Ardmediathek(final PluginWrapper wrapper) {
        super(wrapper);
    }

    private String getURLPart(final String url) {
        return new Regex(url, "https?://[^/]+/(.+)").getMatch(0);
    }

    @Override
    public Class<? extends ArdConfigInterface> getConfigInterface() {
        if ("ardmediathek.de".equalsIgnoreCase(getHost())) {
            return ArdConfigInterface.class;
        } else if ("mediathek.rbb-online.de".equalsIgnoreCase(getHost())) {
            return MediathekRbbOnlineConfig.class;
        } else if ("daserste.de".equalsIgnoreCase(getHost())) {
            return DasersteConfig.class;
        } else if ("mediathek.daserste.de".equalsIgnoreCase(getHost())) {
            return MediathekDasersteConfig.class;
        } else if ("one.ard.de".equalsIgnoreCase(getHost())) {
            return OneARDConfig.class;
        } else if ("wdrmaus.de".equalsIgnoreCase(getHost())) {
            return WDRMausConfig.class;
        } else if ("wdr.de".equalsIgnoreCase(getHost())) {
            return WDRConfig.class;
        } else if ("sportschau.de".equalsIgnoreCase(getHost())) {
            return SportschauConfig.class;
        } else if ("sr-online.de".equalsIgnoreCase(getHost())) {
            return SrOnlineConfig.class;
        } else if ("ndr.de".equalsIgnoreCase(getHost())) {
            return NdrDeConfig.class;
        } else if ("kika.de".equalsIgnoreCase(getHost())) {
            return KikaDeConfig.class;
        } else if ("eurovision.de".equalsIgnoreCase(getHost())) {
            return EurovisionConfig.class;
        } else if ("sputnik.de".equalsIgnoreCase(getHost())) {
            return SputnikDeConfig.class;
        } else if ("checkeins.de".equalsIgnoreCase(getHost())) {
            return CheckeinsDeConfig.class;
        } else if ("sandmann.de".equalsIgnoreCase(getHost())) {
            return SandmannDeConfig.class;
        } else if ("mdr.de".equalsIgnoreCase(getHost())) {
            return MdrDeConfig.class;
        } else if ("rbb-online.de".equalsIgnoreCase(getHost())) {
            return RbbOnlineConfig.class;
        } else {
            return ArdConfigInterface.class;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, final ProgressController progress) throws Exception {
        parameter = Encoding.htmlDecode(param.toString());
        if (parameter.matches(type_unsupported) || parameter.matches(type_invalid)) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        br.setFollowRedirects(true);
        cfg = PluginJsonConfig.get(getConfigInterface());
        final List<String> selectedQualities = new ArrayList<String>();
        /*
         * 2018-03-06: TODO: Maybe add option to download hls audio as hls master playlist will often contain a mp4 stream without video (==
         * audio only).
         */
        final boolean addAudio = cfg.isGrabAudio();
        final boolean addHLS180 = cfg.isGrabHLS180pVideoEnabled();
        final boolean addHLS270 = cfg.isGrabHLS270pVideoEnabled();
        final boolean addHLS270lower = cfg.isGrabHLS270pLowerVideoEnabled();
        final boolean addHLS280 = cfg.isGrabHLS280pVideoEnabled();
        final boolean addHLS360 = cfg.isGrabHLS360pVideoEnabled();
        final boolean addHLS540 = cfg.isGrabHLS540pVideoEnabled();
        final boolean addHLS576 = cfg.isGrabHLS576pVideoEnabled();
        final boolean addHLS720 = cfg.isGrabHLS720pVideoEnabled();
        final boolean addHLS1080 = cfg.isGrabHTTP1080pVideoEnabled();
        grabHLS = addHLS180 || addHLS270lower || addHLS270 || addHLS280 || addHLS360 || addHLS540 || addHLS576 || addHLS720 || addHLS1080;
        if (addHLS180) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("180") + "_180");
        }
        if (addHLS270lower) {
            selectedQualities.add("hls_317000_270");
        }
        if (addHLS270) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("270") + "_270");
        }
        if (addHLS280) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("280") + "_280");
        }
        if (addHLS360) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("360") + "_360");
        }
        if (addHLS540) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("540") + "_540");
        }
        if (addHLS576) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("576") + "_576");
        }
        if (addHLS720) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("720") + "_720");
        }
        if (addHLS1080) {
            selectedQualities.add("hls_" + heigth_to_bitrate.get("1080") + "_1080");
        }
        if (cfg.isGrabHTTP180pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("180") + "_180");
        }
        if (cfg.isGrabHTTP270pLowerVideoEnabled()) {
            selectedQualities.add("http_317000_270");
        }
        if (cfg.isGrabHTTP270pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("270") + "_270");
        }
        if (cfg.isGrabHTTP280pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("280") + "_280");
        }
        if (cfg.isGrabHTTP360pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("360") + "_360");
        }
        if (cfg.isGrabHTTP540pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("540") + "_540");
        }
        if (cfg.isGrabHTTP576pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("576") + "_576");
        }
        if (cfg.isGrabHTTP720pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("720") + "_720");
        }
        if (cfg.isGrabHTTP1080pVideoEnabled()) {
            selectedQualities.add("http_" + heigth_to_bitrate.get("1080") + "_1080");
        }
        if (addAudio) {
            selectedQualities.add("http_0_0");
        }
        try {
            /*
             * 2018-02-22: Important: So far there is only one OLD website, not compatible with the "decryptMediathek" function! Keep this
             * in mind when changing things!
             */
            final String host = this.getHost();
            if (host.equalsIgnoreCase("daserste.de") || host.equalsIgnoreCase("kika.de") || host.equalsIgnoreCase("sputnik.de") || host.equalsIgnoreCase("mdr.de") || host.equalsIgnoreCase("checkeins.de")) {
                decryptDasersteVideo();
            } else if (host.equalsIgnoreCase("ardmediathek.de")) {
                /* 2020-05-26: Separate handling required */
                this.decryptArdmediathekDeNew();
            } else {
                decryptMediathek();
            }
            handleUserQualitySelection(selectedQualities);
        } catch (final DecrypterException e) {
            try {
                if (e.getMessage().equals(EXCEPTION_LINKOFFLINE)) {
                    decryptedLinks.add(this.createOfflinelink(parameter));
                    return decryptedLinks;
                } else if (e.getMessage().equals(EXCEPTION_GEOBLOCKED)) {
                    decryptedLinks.add(this.createOfflinelink(parameter, "GEO-blocked_" + getURLPart(this.parameter), "GEO-blocked_" + getURLPart(this.parameter)));
                    return decryptedLinks;
                }
            } catch (final Exception x) {
            }
            throw e;
        }
        if (decryptedLinks == null) {
            logger.warning("Decrypter out of date for link: " + parameter);
            return null;
        }
        if (decryptedLinks.size() == 0) {
            logger.info("Failed to find any links");
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        return decryptedLinks;
    }

    public static boolean isOffline(final Browser br) {
        return br.getHttpConnection().getResponseCode() == 404;
    }

    /* Returns title for all XML based websites (XML has to be accessed before!) */
    private String getDasersteTitle(final Browser br) {
        final String host = getHost();
        provider = host.substring(0, host.lastIndexOf(".")).replace(".", "_");
        String date = getXML(br.toString(), "broadcastDate");
        if (StringUtils.isEmpty(date)) {
            /* E.g. kika.de */
            date = getXML(br.toString(), "datetimeOfBroadcasting");
        }
        if (StringUtils.isEmpty(date)) {
            /* E.g. mdr.de */
            date = getXML(br.toString(), "broadcastStartDate");
        }
        /* E.g. kika.de */
        show = getXML(br.toString(), "channelName");
        String video_title = getXML(br.toString(), "shareTitle");
        if (StringUtils.isEmpty(video_title)) {
            video_title = getXML(br.toString(), "broadcastName");
        }
        if (StringUtils.isEmpty(video_title)) {
            /* E.g. sputnik.de */
            video_title = getXML(br.toString(), "headline");
        }
        if (StringUtils.isEmpty(video_title)) {
            video_title = "UnknownTitle_" + System.currentTimeMillis();
        }
        this.date_timestamp = getDateMilliseconds(date);
        return video_title;
    }

    /* Returns title, with fallback if nothing found in html */
    private String getMediathekTitle(final Browser brHTML, final Browser brJSON) {
        Map<String, Object> dataEmbeddedContent = null;
        try {
            final String json = brJSON.getRegex("\\$mediaObject\\.jsonpHelper\\.storeAndPlay\\((.*?)\\);").getMatch(0);
            dataEmbeddedContent = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
        } catch (final Throwable e) {
        }
        /* E.g. wdr.de, Tags: schema.org */
        final String jsonSchemaOrg = brHTML.getRegex("<script[^>]*?type=\"application/ld\\+json\"[^>]*?>(.*?)</script>").getMatch(0);
        String title = null;
        /* These RegExes should be compatible with all websites */
        /* Date is already provided in the format we need. */
        String date = brHTML.getRegex("<meta property=\"video:release_date\" content=\"(\\d{4}\\-\\d{2}\\-\\d{2})[^\"]*?\"[^>]*?/?>").getMatch(0);
        if (date == null) {
            date = brHTML.getRegex("<span itemprop=\"datePublished\" content=\"(\\d{4}\\-\\d{2}\\-\\d{2})[^\"]*?\"[^>]*?/?>").getMatch(0);
        }
        String description = brHTML.getRegex("<meta property=\"og:description\" content=\"([^\"]+)\"").getMatch(0);
        final String host = getHost();
        if (jsonSchemaOrg != null) {
            /* 2018-02-15: E.g. daserste.de, wdr.de */
            final String headline = brHTML.getRegex("<h3 class=\"headline\">([^<>]+)</h3>").getMatch(0);
            try {
                LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaMap(jsonSchemaOrg);
                final String uploadDate = (String) entries.get("uploadDate");
                title = (String) entries.get("name");
                if ("Video".equalsIgnoreCase(title) && !StringUtils.isEmpty(headline)) {
                    /**
                     * 2018-02-22: Some of these schema-objects contain wrong information e.g.
                     * https://www1.wdr.de/mediathek/video/klangkoerper/klangkoerper/video-wdr-dackl-jazzkonzert-100.html --> This is a
                     * simple fallback.
                     */
                    title = headline;
                }
                if (description == null) {
                    description = (String) entries.get("description");
                }
                if (StringUtils.isEmpty(date) && !StringUtils.isEmpty(uploadDate)) {
                    /* Fallback */
                    date = new Regex(uploadDate, "(\\d{4}\\-\\d{2}\\-\\d{2})").getMatch(0);
                }
                /* Find more data */
                entries = (LinkedHashMap<String, Object>) entries.get("productionCompany");
                if (entries != null) {
                    provider = (String) entries.get("name");
                }
            } catch (final Throwable e) {
            }
            if (StringUtils.isEmpty(title) && headline != null) {
                /* 2018-04-11: ardmediathek.de */
                title = headline;
            }
        } else if (host.equalsIgnoreCase("wdrmaus.de")) {
            final String content_ids_str = brHTML.getRegex("var _contentId = \\[([^<>\\[\\]]+)\\];").getMatch(0);
            if (content_ids_str != null) {
                final String[] content_ids = content_ids_str.split(",");
                if (content_ids != null && content_ids.length >= 3) {
                    show = content_ids[0];
                    title = content_ids[2];
                }
            }
            if (StringUtils.isEmpty(title)) {
                title = brHTML.getRegex("<title>([^<>]+) \\- Die Sendung mit der Maus \\- WDR</title>").getMatch(0);
            }
            if (StringUtils.isEmpty(show) && (brHTML.getURL().contains("/lachgeschichten") || brHTML.getURL().contains("/sachgeschichten"))) {
                // show = "Die Sendung mit der Maus";
                show = "Lach- und Sachgeschichten";
            }
            /*
             * 2018-02-22: TODO: This may sometimes be inaccurate when there are multiple videoObjects on one page (rare case) e.g.
             * http://www.wdrmaus.de/extras/mausthemen/eisenbahn/index.php5 --> This is so far not a real usage case and we do not have any
             * complaints about the current plugin behavior!
             */
            if (StringUtils.isEmpty(date)) {
                date = brHTML.getRegex("Sendetermin: (\\d{2}\\.\\d{2}\\.\\d{4})").getMatch(0);
            }
            if (StringUtils.isEmpty(date)) {
                /* Last chance */
                date = PluginJSonUtils.getJson(brJSON, "trackerClipAirTime");
            }
        } else if (dataEmbeddedContent != null) {
            /* E.g. type_embedded --> deviceids-medp-id1.wdr.de/ondemand/123/1234567.js */
            dataEmbeddedContent = (Map<String, Object>) dataEmbeddedContent.get("trackerData");
            // final String trackerClipCategory = (String) dataEmbeddedContent.get("trackerClipCategory");
            show = (String) dataEmbeddedContent.get("trackerClipSubcategory");
            title = (String) dataEmbeddedContent.get("trackerClipTitle");
            final String trackerClipAirTime = (String) dataEmbeddedContent.get("trackerClipAirTime");
            date = (String) dataEmbeddedContent.get("trackerClipAirTime");
        } else if (host.contains("sr-online.de")) {
            /* sr-mediathek.sr-online.de */
            title = brHTML.getRegex("<div class=\"ardplayer\\-title\">([^<>\"]+)</div>").getMatch(0);
            if (StringUtils.isEmpty(date)) {
                date = brHTML.getRegex("<p>Video \\| (\\d{2}\\.\\d{2}\\.\\d{4}) \\| Dauer:").getMatch(0);
            }
        } else if (host.equalsIgnoreCase("ndr.de") || host.equalsIgnoreCase("eurovision.de")) {
            /* ndr.de */
            if (brHTML.getURL().contains("daserste.ndr.de") && StringUtils.isEmpty(date)) {
                date = brHTML.getRegex("<p>Dieses Thema im Programm:</p>\\s*?<h2>[^<>]*?(\\d{2}\\.\\d{2}\\.\\d{4})[^<>]*?</h2>").getMatch(0);
            }
            title = brHTML.getRegex("<meta property=\"og:title\" content=\"([^<>\"]+)\"/>").getMatch(0);
            if (StringUtils.isEmpty(date)) {
                /* Last chance */
                date = PluginJSonUtils.getJson(brJSON, "assetid");
                if (!StringUtils.isEmpty(date)) {
                    date = new Regex(date, "TV\\-(\\d{8})").getMatch(0);
                }
            }
        } else {
            /* E.g. ardmediathek.de */
            String newjson = brHTML.getRegex("window\\.__APOLLO_STATE__ = (\\{.*?);\\s+").getMatch(0);
            if (newjson == null) {
                newjson = br.toString();
            }
            show = PluginJSonUtils.getJson(newjson, "show");
            title = PluginJSonUtils.getJson(newjson, "clipTitle");
            if (StringUtils.isEmpty(title)) {
                /* 2021-04-07: wdr.de */
                title = PluginJSonUtils.getJson(newjson, "trackerClipTitle");
            }
            if (title == null) {
                title = br.getRegex("<meta name\\s*=\\s*\"dcterms.title\"\\s*content\\s*=\\s*\"(.*?)\"").getMatch(0);
            }
            if (date == null) {
                date = PluginJSonUtils.getJson(newjson, "broadcastedOn");
            }
        }
        this.date_timestamp = getDateMilliseconds(date);
        if (StringUtils.isEmpty(title)) {
            /* This should never happen */
            title = "UnknownTitle_" + UniqueAlltimeID.create();
        }
        title = title.trim();
        if (StringUtils.isEmpty(provider)) {
            /* Fallback */
            provider = host.substring(0, host.lastIndexOf(".")).replace(".", "_");
        }
        title = Encoding.htmlDecode(title);
        title = encodeUnicode(title);
        return title;
    }

    /** Find xml URL which leads to subtitle and video stream URLs. */
    private String getVideoXMLURL() throws Exception {
        final String host = getHost();
        String url_xml = null;
        if (host.equalsIgnoreCase("daserste.de") || host.equalsIgnoreCase("checkeins.de")) {
            /* The fast way - we do not even have to access the main URL which the user has added :) */
            url_xml = parameter.replace(".html", "~playerXml.xml");
        } else if (this.parameter.matches(".+mdr\\.de/.+/((?:video|audio)\\-\\d+)\\.html")) {
            /* Some special mdr.de URLs --> We do not have to access main URL so this way we can speed up the crawl process a bit :) */
            this.contentID = new Regex(this.parameter, "((?:audio|video)\\-\\d+)\\.html$").getMatch(0);
            url_xml = String.format("https://www.mdr.de/mediathek/mdr-videos/d/%s-avCustom.xml", this.contentID);
        } else {
            /* E.g. kika.de, sputnik.de, mdr.de */
            br.getPage(this.parameter);
            if (isOffline(this.br)) {
                throw new DecrypterException(EXCEPTION_LINKOFFLINE);
            }
            url_xml = br.getRegex("\\'((?:https?://|(?:\\\\)?/)[^<>\"]+\\-avCustom\\.xml)\\'").getMatch(0);
            if (!StringUtils.isEmpty(url_xml)) {
                if (url_xml.contains("\\")) {
                    url_xml = url_xml.replace("\\", "");
                }
                this.contentID = new Regex(url_xml, "((?:audio|video)\\-\\d+)").getMatch(0);
            }
        }
        return url_xml;
    }

    /** Finds json URL which leads to subtitle and video stream URLs AND sets unique contentID. */
    private String getVideoJsonURL() throws MalformedURLException {
        String url_json = null;
        final String host = getHost();
        if (host.contains("sr-online.de")) {
            this.contentID = new Regex(br.getURL(), "id=(\\d+)").getMatch(0);
            url_json = String.format("http://www.sr-mediathek.de/sr_player/mc.php?id=%s&tbl=&pnr=0&hd=1&devicetype=", this.contentID);
        } else if (host.equalsIgnoreCase("sandmann.de")) {
            url_json = br.getRegex("data\\-media\\-ref=\"([^\"]*?\\.jsn)[^\"]*?\"").getMatch(0);
            if (!StringUtils.isEmpty(url_json)) {
                if (url_json.startsWith("/")) {
                    url_json = "https://www.sandmann.de" + url_json;
                }
                /* This is a very ugly contentID */
                this.contentID = new Regex(url_json, "sandmann\\.de/(.+)").getMatch(0);
            }
        } else if (host.contains("ndr.de") || host.equalsIgnoreCase("eurovision.de")) {
            /* E.g. daserste.ndr.de, blabla.ndr.de */
            this.contentID = br.getRegex("([A-Za-z0-9]+\\d+)\\-(?:ard)?player_[^\"]+\"").getMatch(0);
            if (!StringUtils.isEmpty(this.contentID)) {
                url_json = String.format("https://www.ndr.de/%s-ardjson.json", this.contentID);
            }
        } else {
            /* wdr.de, one.ard.de, wdrmaus.de */
            url_json = this.br.getRegex("(?:\\'|\")mediaObj(?:\\'|\"):\\s*?\\{\\s*?(?:\\'|\")url(?:\\'|\"):\\s*?(?:\\'|\")(https?://[^<>\"]+\\.js)(?:\\'|\")").getMatch(0);
            if (url_json != null) {
                /* 2018-03-07: Same IDs that will also appear in every streamingURL! */
                this.contentID = new Regex(url_json, "(\\d+/\\d+)\\.js$").getMatch(0);
            }
        }
        return url_json;
    }

    /**
     * Find subtitle URL inside xml String
     */
    private String getXMLSubtitleURL(final Browser xmlBR) throws IOException {
        String subtitleURL = getXML(xmlBR.toString(), "videoSubtitleUrl");
        if (StringUtils.isEmpty(subtitleURL)) {
            /* E.g. checkeins.de */
            subtitleURL = xmlBR.getRegex("<dataTimedTextNoOffset url=\"((?:https:)?[^<>\"]+\\.xml)\">").getMatch(0);
        }
        if (subtitleURL != null) {
            return xmlBR.getURL(subtitleURL).toString();
        } else {
            return null;
        }
    }

    /**
     * Find subtitle URL inside json String
     *
     * @throws MalformedURLException
     */
    private String getJsonSubtitleURL(final Browser jsonBR) throws IOException {
        String subtitleURL;
        if (br.getURL().contains("wdr.de/")) {
            subtitleURL = PluginJSonUtils.getJsonValue(jsonBR, "captionURL");
            if (subtitleURL == null) {
                // TODO: check other formats
                subtitleURL = PluginJSonUtils.getJsonValue(jsonBR, "xml");
            }
        } else {
            subtitleURL = PluginJSonUtils.getJson(jsonBR, "_subtitleUrl");
        }
        if (subtitleURL != null) {
            return jsonBR.getURL(subtitleURL).toString();
        } else {
            return null;
        }
    }

    private String getHlsToHttpURLFormat(final String hls_master) {
        final Regex regex_hls = new Regex(hls_master, ".+/([^/]+/[^/]+/[^,/]+)(?:/|_|\\.),([A-Za-z0-9_,\\-]+),\\.mp4\\.csmil/?");
        String urlpart = regex_hls.getMatch(0);
        String urlpart2 = new Regex(hls_master, "//[^/]+/[^/]+/(.*?)(?:/|_),").getMatch(0);
        String http_url_format = null;
        /**
         * hls --> http urls (whenever possible) <br />
         */
        /* First case */
        if (hls_master.contains("sr_hls_od-vh") && urlpart != null) {
            http_url_format = "http://mediastorage01.sr-online.de/Video/" + urlpart + "_%s.mp4";
        }
        /* 2020-06-02: Do NOT yet try to make a generic RegEx for all types of HLS URLs!! */
        final String pattern_ard = ".*//hlsodswr-vh\\.akamaihd\\.net/i/(.*?),.*?\\.mp4\\.csmil/master\\.m3u8";
        final String pattern_hr = ".*//hrardmediathek-vh\\.akamaihd.net/i/(.*?),.+\\.mp4\\.csmil/master\\.m3u8$";
        /* Daserste */
        if (hls_master.contains("dasersteuni-vh.akamaihd.net")) {
            if (urlpart2 != null) {
                http_url_format = "https://pdvideosdaserste-a.akamaihd.net/" + urlpart2 + "/%s.mp4";
            }
        } else if (hls_master.contains("br-i.akamaihd.net")) {
            if (urlpart2 != null) {
                http_url_format = "http://cdn-storage.br.de/" + urlpart2 + "_%s.mp4";
            }
        } else if (hls_master.contains("wdradaptiv-vh.akamaihd.net") && urlpart2 != null) {
            /* wdr */
            http_url_format = "http://wdrmedien-a.akamaihd.net/" + urlpart2 + "/%s.mp4";
        } else if (hls_master.contains("rbbmediaadp-vh") && urlpart2 != null) {
            /* For all RBB websites e.g. also sandmann.de */
            http_url_format = "https://rbbmediapmdp-a.akamaihd.net/" + urlpart2 + "_%s.mp4";
        } else if (hls_master.contains("ndrod-vh.akamaihd.net") && urlpart != null) {
            /* 2018-03-07: There is '/progressive/' and '/progressive_geo/' --> We have to grab this from existing http urls */
            final String server_http = br.getRegex("(https?://mediandr\\-a\\.akamaihd\\.net/progressive[^/]*?/)[^\"]+\\.mp4").getMatch(0);
            if (server_http != null) {
                http_url_format = server_http + urlpart + ".%s.mp4";
            }
        } else if (new Regex(hls_master, pattern_ard).matches()) {
            urlpart = new Regex(hls_master, pattern_ard).getMatch(0);
            http_url_format = "https://pdodswr-a.akamaihd.net/" + urlpart + "%s.mp4";
        } else if (new Regex(hls_master, pattern_hr).matches()) {
            urlpart = new Regex(hls_master, pattern_hr).getMatch(0);
            http_url_format = "http://hrardmediathek-a.akamaihd.net/" + urlpart + "%skbit.mp4";
        } else {
            /* Unsupported URL */
            logger.warning("Warning: Unsupported HLS pattern, cannot create HTTP URL!");
        }
        return http_url_format;
    }

    private void decryptArdmediathekDeNew() throws Exception {
        /* E.g. old classic.ardmediathek.de URLs */
        final boolean requiresOldContentIDHandling;
        String ardDocumentID = new Regex(this.parameter, "documentId=(\\d+)").getMatch(0);
        Map<String, Object> entries = null;
        if (ardDocumentID != null) {
            requiresOldContentIDHandling = true;
            this.title = ardDocumentID;
            this.contentID = ardDocumentID;
        } else {
            requiresOldContentIDHandling = false;
            String ardBase64;
            final String pattern_player = ".+/player/([^/]+).*";
            if (parameter.matches(pattern_player)) {
                /* E.g. URLs that are a little bit older */
                ardBase64 = new Regex(this.parameter, pattern_player).getMatch(0);
            } else {
                /* New URLs */
                ardBase64 = new Regex(this.parameter, "/([^/]+)/?$").getMatch(0);
            }
            if (ardBase64 == null) {
                /* This should never happen */
                throw new DecrypterException(EXCEPTION_LINKOFFLINE);
            }
            if (Encoding.isUrlCoded(ardBase64)) {
                ardBase64 = Encoding.urlDecode(ardBase64, true);
            }
            /* Check if we really have a base64 String otherwise we can abort right away */
            final String ardBase64Decoded = Encoding.Base64Decode(ardBase64);
            if (StringUtils.equals(ardBase64, ardBase64Decoded)) {
                logger.info("Unsupported URL (?)");
                throw new DecrypterException(EXCEPTION_LINKOFFLINE);
            }
            br.getPage("https://page.ardmediathek.de/page-gateway/pages/daserste/item/" + Encoding.urlEncode(ardBase64) + "?devicetype=pc&embedded=false");
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new DecrypterException(EXCEPTION_LINKOFFLINE);
            }
            ardDocumentID = PluginJSonUtils.getJson(br, "contentId");
            // final ArrayList<Object> ressourcelist = (ArrayList<Object>) entries.get("");
            entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "widgets/{0}/");
            final String broadcastedOn = (String) entries.get("broadcastedOn");
            final String ardtitle = (String) entries.get("title");
            final String showname = (String) JavaScriptEngineFactory.walkJson(entries, "show/title");
            final String type = (String) entries.get("type");
            if ("player_live".equalsIgnoreCase(type)) {
                logger.info("Cannot download livestreams");
                throw new DecrypterException(EXCEPTION_LINKOFFLINE);
            } else if (StringUtils.isEmpty(broadcastedOn)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else if (StringUtils.isAllEmpty(ardtitle, showname)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String date_formatted = new Regex(broadcastedOn, "(\\d{4}-\\d{2}-\\d{2})").getMatch(0);
            if (date_formatted == null) {
                /* Fallback */
                date_formatted = broadcastedOn;
            }
            if (StringUtils.isAllNotEmpty(showname, ardtitle)) {
                this.title = showname + " - " + ardtitle;
            } else if (StringUtils.isEmpty(showname)) {
                this.title = ardtitle;
            } else {
                this.title = showname;
            }
            this.date_timestamp = getDateMilliseconds(broadcastedOn);
            if (ardDocumentID != null) {
                /* Required for linkid / dupe check */
                this.contentID = ardDocumentID;
            }
            packagename = date_formatted + "_ardmediathek_de_" + this.title;
        }
        if (requiresOldContentIDHandling) {
            if (StringUtils.isEmpty(ardDocumentID)) {
                /* Probably offline content */
                throw new DecrypterException(EXCEPTION_LINKOFFLINE);
            }
            /* 2020-05-26: Also possible: http://page.ardmediathek.de/page-gateway/playerconfig/<documentID> */
            /* Old way: http://www.ardmediathek.de/play/media/%s?devicetype=pc&features=flash */
            br.getPage(String.format("http://page.ardmediathek.de/page-gateway/mediacollection/%s?devicetype=pc", ardDocumentID));
            entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "mediaCollection/embedded");
        } else {
            entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        }
        crawlARDJson(entries);
    }

    /** Last revision with old handling: 38658 */
    private void decryptMediathek() throws Exception {
        br.getPage(parameter);
        if (isOffline(this.br)) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        final Browser brBefore = br.cloneBrowser();
        if (this.parameter.matches(type_embedded)) {
            /* Embedded content --> json URL has already been accessed */
            // brJSON = br.cloneBrowser();
            /* Do nothing */
        } else {
            final String[] embeddedVideosType1 = br.getRegex("(?:\\'|\")mediaObj(?:\\'|\"):\\s*?\\{\\s*?(?:\\'|\")url(?:\\'|\"):\\s*?(?:\\'|\")(https?://[^<>\"]+\\.js)(?:\\'|\")").getColumn(0);
            if (embeddedVideosType1.length > 1) {
                /* Embedded items --> Go back into decrypter */
                logger.info("Found multiple embedded items");
                for (final String embeddedVideo : embeddedVideosType1) {
                    decryptedLinks.add(this.createDownloadlink(embeddedVideo));
                }
                return;
            }
            br.setFollowRedirects(true);
            final String url_json;
            if (embeddedVideosType1.length == 1) {
                url_json = embeddedVideosType1[0];
            } else {
                url_json = getVideoJsonURL();
            }
            if (StringUtils.isEmpty(url_json)) {
                /* No downloadable content --> URL should be offline (or only text content) */
                /* 2021-04-07: Check for special case e.g. for wdr.de */
                final String json = br.getRegex("globalObject\\.gseaInlineMediaData\\[\"mdb-\\d+\"\\] =\\s*(\\{.*?\\});\\s*</script>").getMatch(0);
                if (json == null) {
                    throw new DecrypterException(EXCEPTION_LINKOFFLINE);
                }
                br.getRequest().setHtmlCode(json);
            } else {
                br.getPage(url_json);
                /* No json --> No media to crawl (rare case!)! */
                if (!br.getHttpConnection().getContentType().contains("application/json") && !br.getHttpConnection().getContentType().contains("application/javascript") && !br.containsHTML("\\{") || br.getHttpConnection().getResponseCode() == 404 || br.toString().length() <= 10) {
                    throw new DecrypterException(EXCEPTION_LINKOFFLINE);
                }
            }
        }
        title = getMediathekTitle(brBefore, this.br);
        Object entries = null;
        try {
            entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        } catch (final Throwable e) {
        }
        crawlARDJson(entries);
    }

    private void crawlARDJson(final Object mediaCollection) throws Exception {
        /* We know how their http urls look - this way we can avoid HDS/HLS/RTMP */
        /*
         * http://adaptiv.wdr.de/z/medp/ww/fsk0/104/1046579/,1046579_11834667,1046579_11834665,1046579_11834669,.mp4.csmil/manifest.f4
         */
        // //wdradaptiv-vh.akamaihd.net/i/medp/ondemand/weltweit/fsk0/139/1394333/,1394333_16295554,1394333_16295556,1394333_16295555,1394333_16295557,1394333_16295553,1394333_16295558,.mp4.csmil/master.m3u8
        /*
         * Grab all http qualities inside json
         */
        subtitleLink = getJsonSubtitleURL(this.br);
        final List<String> httpStreamsQualityIdentifiers = new ArrayList<String>();
        /* For http stream quality identifiers which have been created by the hls --> http URLs converter */
        final List<String> httpStreamsQualityIdentifiers_2_over_hls_master = new ArrayList<String>();
        Map<String, Object> map;
        if (mediaCollection instanceof Map) {
            map = (Map<String, Object>) mediaCollection;
            if (!map.containsKey("_mediaArray")) {
                /* 2020-06-08: For new ARD URLs */
                map = (Map<String, Object>) JavaScriptEngineFactory.walkJson(map, "widgets/{0}/mediaCollection/embedded");
            }
        } else {
            map = null;
        }
        if (map != null && map.containsKey("_mediaArray")) {
            /*
             * Website actually tries to stream video - only then it is safe to know if the items is only "somewhere" GEO-blocked or
             * GEO-blocked for the current user/IP!
             */
            // final boolean geoBlocked = ((Boolean) map.get("_geoblocked")).booleanValue();
            // if (geoBlocked) {
            // /* 2020-11-19: Direct-URLs are given but will all redirect to a "GEO-blocked" video so let's stop here! */
            // throw new DecrypterException(EXCEPTION_GEOBLOCKED);
            // }
            try {
                final List<Map<String, Object>> mediaArray = (List<Map<String, Object>>) map.get("_mediaArray");
                for (Map<String, Object> media : mediaArray) {
                    List<Map<String, Object>> mediaStreamArray = (List<Map<String, Object>>) media.get("_mediaStreamArray");
                    for (Map<String, Object> mediaStream : mediaStreamArray) {
                        final int quality;
                        if (mediaStream.get("_quality") instanceof Number) {
                            quality = ((Number) mediaStream.get("_quality")).intValue();
                        } else {
                            continue;
                        }
                        final List<String> streams;
                        if (mediaStream.get("_stream") instanceof String) {
                            streams = new ArrayList<String>();
                            streams.add((String) mediaStream.get("_stream"));
                        } else {
                            streams = ((List<String>) mediaStream.get("_stream"));
                        }
                        for (int index = 0; index < streams.size(); index++) {
                            final String stream = streams.get(index);
                            if (stream == null || !StringUtils.endsWithCaseInsensitive(stream, ".mp4")) {
                                /* Skip invalid objects */
                                continue;
                            }
                            final String url = br.getURL(stream).toString();
                            final int widthInt;
                            final int heightInt;
                            /*
                             * Sometimes the resolutions is given, sometimes we have to assume it and sometimes (e.g. HLS streaming) there
                             * are multiple qualities available for one stream URL.
                             */
                            if (mediaStream.containsKey("_width") && mediaStream.containsKey("_height")) {
                                widthInt = ((Number) mediaStream.get("_width")).intValue();
                                heightInt = ((Number) mediaStream.get("_height")).intValue();
                            } else if (quality == 0 && streams.size() == 1) {
                                widthInt = 320;
                                heightInt = 180;
                            } else if (quality == 1 && streams.size() == 1) {
                                widthInt = 512;
                                heightInt = 288;
                            } else if (quality == 1 && streams.size() == 2) {
                                switch (index) {
                                case 0:
                                    widthInt = 512;
                                    heightInt = 288;
                                    break;
                                case 1:
                                default:
                                    widthInt = 480;
                                    heightInt = 270;
                                    break;
                                }
                            } else if (quality == 2 && streams.size() == 1) {
                                widthInt = 960;
                                heightInt = 544;
                            } else if (quality == 2 && streams.size() == 2) {
                                switch (index) {
                                case 0:
                                    widthInt = 640;
                                    heightInt = 360;
                                    break;
                                case 1:
                                default:
                                    widthInt = 960;
                                    heightInt = 540;
                                    break;
                                }
                            } else if (quality == 3 && streams.size() == 1) {
                                widthInt = 960;
                                heightInt = 540;
                            } else if (quality == 3 && streams.size() == 2) {
                                switch (index) {
                                case 0:
                                    widthInt = 1280;
                                    heightInt = 720;
                                    break;
                                case 1:
                                default:
                                    widthInt = 960;
                                    heightInt = 540;
                                    break;
                                }
                            } else if (StringUtils.containsIgnoreCase(stream, "0.mp4") || StringUtils.containsIgnoreCase(stream, "128k.mp4")) {
                                widthInt = 320;
                                heightInt = 180;
                            } else if (StringUtils.containsIgnoreCase(stream, "lo.mp4")) {
                                widthInt = 256;
                                heightInt = 144;
                            } else if (StringUtils.containsIgnoreCase(stream, "A.mp4") || StringUtils.containsIgnoreCase(stream, "mn.mp4") || StringUtils.containsIgnoreCase(stream, "256k.mp4")) {
                                widthInt = 480;
                                heightInt = 270;
                            } else if (StringUtils.containsIgnoreCase(stream, "B.mp4") || StringUtils.containsIgnoreCase(stream, "hi.mp4") || StringUtils.containsIgnoreCase(stream, "512k.mp4")) {
                                widthInt = 512;
                                heightInt = 288;
                            } else if (StringUtils.containsIgnoreCase(stream, "C.mp4") || StringUtils.containsIgnoreCase(stream, "hq.mp4") || StringUtils.containsIgnoreCase(stream, "1800k.mp4")) {
                                widthInt = 960;
                                heightInt = 540;
                            } else if (StringUtils.containsIgnoreCase(stream, "E.mp4") || StringUtils.containsIgnoreCase(stream, "ln.mp4") || StringUtils.containsIgnoreCase(stream, "1024k.mp4") || StringUtils.containsIgnoreCase(stream, "1.mp4")) {
                                widthInt = 640;
                                heightInt = 360;
                            } else if (StringUtils.containsIgnoreCase(stream, "X.mp4") || StringUtils.containsIgnoreCase(stream, "hd.mp4")) {
                                widthInt = 1280;
                                heightInt = 720;
                            } else {
                                /*
                                 * Fallback to 'old' handling which could result in wrong resolutions (but that's better than missing
                                 * downloadlinks!)
                                 */
                                final Object width = mediaStream.get("_width");
                                final Object height = mediaStream.get("_height");
                                if (width instanceof Number) {
                                    widthInt = ((Number) width).intValue();
                                } else {
                                    switch (((Number) quality).intValue()) {
                                    case 0:
                                        widthInt = 320;
                                        break;
                                    case 1:
                                        widthInt = 512;
                                        break;
                                    case 2:
                                        widthInt = 640;
                                        break;
                                    case 3:
                                        widthInt = 1280;
                                        break;
                                    default:
                                        widthInt = -1;
                                        break;
                                    }
                                }
                                if (width instanceof Number) {
                                    heightInt = ((Number) height).intValue();
                                } else {
                                    switch (((Number) quality).intValue()) {
                                    case 0:
                                        heightInt = 180;
                                        break;
                                    case 1:
                                        heightInt = 288;
                                        break;
                                    case 2:
                                        heightInt = 360;
                                        break;
                                    case 3:
                                        heightInt = 720;
                                        break;
                                    default:
                                        heightInt = -1;
                                        break;
                                    }
                                }
                            }
                            final DownloadLink download = addQuality(url, null, 0, widthInt, heightInt, foundQualitiesMap);
                            if (download != null) {
                                httpStreamsQualityIdentifiers.add(getQualityIdentifier(url, 0, widthInt, heightInt));
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                logger.log(e);
            }
        }
        /*
         * TODO: It might only make sense to attempt this if we found more than 3 http qualities previously because usually 3 means we will
         * also only have 3 hls qualities --> There are no additional http qualities!
         */
        String http_url_audio = br.getRegex("((?:https?:)?//[^<>\"]+\\.mp3)\"").getMatch(0);
        final String hls_master = br.getRegex("(//[^<>\"]+\\.m3u8[^<>\"]*?)").getMatch(0);
        final String quality_string = new Regex(hls_master, ".*?/i/.*?,([A-Za-z0-9_,\\-\\.]+),?\\.mp4\\.csmil.*?").getMatch(0);
        if (StringUtils.isEmpty(hls_master) && http_url_audio == null && httpStreamsQualityIdentifiers.size() == 0) {
            logger.warning("Decrypter broken for link: " + parameter);
            throw new DecrypterException("Plugin broken");
        }
        /*
         * This is a completely different attempt to find HTTP URLs. As long as it works, this may be more reliable than everything above
         * here!
         */
        final boolean tryToFindAdditionalHTTPURLs = true;
        if (tryToFindAdditionalHTTPURLs && hls_master != null) {
            final String http_url_format = getHlsToHttpURLFormat(hls_master);
            final String[] qualities_hls = quality_string != null ? quality_string.split(",") : null;
            if (http_url_format != null && qualities_hls != null && qualities_hls.length > 0) {
                /* Access HLS master to find correct resolution for each ID (the only possible way) */
                URLConnectionAdapter con = null;
                try {
                    con = br.openGetConnection("http:" + hls_master);
                    if (con.getURL().toString().contains("/static/geoblocking.mp4")) {
                        throw new DecrypterException(EXCEPTION_GEOBLOCKED);
                    }
                    br.followConnection();
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
                final String[] resolutionsInOrder = br.getRegex("RESOLUTION=(\\d+x\\d+)").getColumn(0);
                if (resolutionsInOrder != null) {
                    logger.info("Crawling additional http urls");
                    for (int counter = 0; counter <= qualities_hls.length - 1; counter++) {
                        if (counter > qualities_hls.length - 1 || counter > resolutionsInOrder.length - 1) {
                            break;
                        }
                        final String quality_id = qualities_hls[counter];
                        final String final_url = String.format(http_url_format, quality_id);
                        // final String linkid = qualities[counter];
                        final String resolution = resolutionsInOrder[counter];
                        final String[] height_width = resolution.split("x");
                        final String width = height_width[0];
                        final String height = height_width[1];
                        final int widthInt = Integer.parseInt(width);
                        final int heightInt = Integer.parseInt(height);
                        final String qualityIdentifier = getQualityIdentifier(final_url, 0, widthInt, heightInt);
                        if (!httpStreamsQualityIdentifiers_2_over_hls_master.contains(qualityIdentifier)) {
                            logger.info("Found (additional) http quality via HLS Master: " + qualityIdentifier);
                            addQuality(final_url, null, 0, widthInt, heightInt, foundQualitiesMap_http_urls_via_HLS_master);
                            httpStreamsQualityIdentifiers_2_over_hls_master.add(qualityIdentifier);
                        }
                    }
                }
            }
            /*
             * Decide whether we want to use the existing http URLs or whether we want to prefer the ones we've generated out of their HLS
             * URLs.
             */
            final int numberof_http_qualities_found_inside_json = foundQualitiesMap.keySet().size();
            final int numberof_http_qualities_found_via_hls_to_http_conversion = foundQualitiesMap_http_urls_via_HLS_master.keySet().size();
            if (numberof_http_qualities_found_via_hls_to_http_conversion > numberof_http_qualities_found_inside_json) {
                /*
                 * 2019-04-15: Prefer URLs created via this way because if we don't, we may get entries labled as different qualities which
                 * may be duplicates!
                 */
                logger.info(String.format("Found [%d] qualities via HLS --> HTTP conversion which is more than number of http URLs inside json [%d]", numberof_http_qualities_found_via_hls_to_http_conversion, numberof_http_qualities_found_inside_json));
                logger.info("--> Using converted URLs instead");
                foundQualitiesMap.clear();
                foundQualitiesMap.putAll(foundQualitiesMap_http_urls_via_HLS_master);
            }
        }
        if (hls_master != null) {
            addHLS(br, hls_master);
        }
        if (http_url_audio != null) {
            if (http_url_audio.startsWith("//")) {
                /* 2019-04-11: Workaround for missing protocol */
                http_url_audio = "https:" + http_url_audio;
            }
            addQuality(http_url_audio, null, 0, 0, 0, foundQualitiesMap);
        }
    }

    /* INFORMATION: network = akamai or limelight == RTMP */
    private void decryptDasersteVideo() throws Exception {
        br.getPage(parameter);
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        setBrowserExclusive();
        br.setFollowRedirects(true);
        final String xml_URL = getVideoXMLURL();
        if (xml_URL == null) {
            /* Probably no downloadable content available */
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        br.getPage(xml_URL);
        /* Usually daserste.de and checkeins.de as there is no way to find a contentID inside URL added by the user. */
        final String id = br.getRegex("<c7>(.*?)</c7>").getMatch(0);
        if (id != null && this.contentID == null) {
            contentID = Hash.getSHA1(id);
        }
        if (br.getHttpConnection().getResponseCode() == 404 || !br.getHttpConnection().getContentType().contains("xml")) {
            throw new DecrypterException(EXCEPTION_LINKOFFLINE);
        }
        this.subtitleLink = getXMLSubtitleURL(this.br);
        /* E.g. checkeins.de */
        final String fskRating = this.br.getRegex("<fskRating>fsk(\\d+)</fskRating>").getMatch(0);
        if (fskRating != null && Short.parseShort(fskRating) >= 12) {
            /* Video is age restricted --> Only available from >=8PM. */
            decryptedLinks.add(this.createOfflinelink(parameter, "FSK_BLOCKED"));
            return;
        }
        this.title = getDasersteTitle(this.br);
        final ArrayList<String> hls_master_dupelist = new ArrayList<String>();
        final String[] mediaStreamArray = br.getRegex("(<asset.*?</asset>)").getColumn(0);
        for (final String stream : mediaStreamArray) {
            /* E.g. kika.de */
            final String hls_master;
            String http_url = getXML(stream, "progressiveDownloadUrl");
            if (StringUtils.isEmpty(http_url)) {
                /* E.g. daserste.de */
                http_url = getXML(stream, "fileName");
            }
            /* E.g. daserste.de */
            String filesize = getXML(stream, "size");
            if (StringUtils.isEmpty(filesize)) {
                /* E.g. kika.de */
                filesize = getXML(stream, "fileSize");
            }
            final String bitrate_video = getXML(stream, "bitrateVideo");
            final String bitrate_audio = getXML(stream, "bitrateAudio");
            final String width_str = getXML(stream, "frameWidth");
            final String height_str = getXML(stream, "frameHeight");
            /* This sometimes contains resolution: e.g. <profileName>Video 2018 | MP4 720p25 | Web XL| 16:9 | 1280x720</profileName> */
            final String profileName = getXML(stream, "profileName");
            final String resolutionInProfileName = new Regex(profileName, "(\\d+x\\d+)").getMatch(0);
            int width = 0;
            int height = 0;
            if (width_str != null && width_str.matches("\\d+")) {
                width = Integer.parseInt(width_str);
            }
            if (height_str != null && height_str.matches("\\d+")) {
                height = Integer.parseInt(height_str);
            }
            if (width == 0 && height == 0 && resolutionInProfileName != null) {
                final String[] resInfo = resolutionInProfileName.split("x");
                width = Integer.parseInt(resInfo[0]);
                height = Integer.parseInt(resInfo[1]);
            }
            if (StringUtils.isEmpty(http_url) || isUnsupportedProtocolDasersteVideo(http_url) || !http_url.startsWith("http")) {
                continue;
            }
            if (http_url.contains(".m3u8")) {
                hls_master = http_url;
                http_url = null;
            } else {
                /* hls master is stored in separate tag e.g. kika.de */
                hls_master = getXML(stream, "adaptiveHttpStreamingRedirectorUrl");
            }
            /* HLS master url may exist in every XML item --> We only have to add all HLS qualities once! */
            if (!StringUtils.isEmpty(hls_master) && !hls_master_dupelist.contains(hls_master)) {
                /* HLS */
                addHLS(this.br, hls_master);
                hls_master_dupelist.add(hls_master);
            }
            if (!StringUtils.isEmpty(http_url)) {
                /* http */
                long bitrate;
                final String bitrateFromURLStr = new Regex(http_url, "(\\d+)k").getMatch(0);
                if (!StringUtils.isEmpty(bitrate_video) && !StringUtils.isEmpty(bitrate_audio)) {
                    bitrate = Long.parseLong(bitrate_video) + Long.parseLong(bitrate_audio);
                    if (bitrate < 10000) {
                        bitrate = bitrate * 1000;
                    }
                } else if (bitrateFromURLStr != null) {
                    bitrate = Long.parseLong(bitrateFromURLStr);
                } else {
                    bitrate = 0;
                }
                addQualityDasersteVideo(http_url, filesize, bitrate, width, height);
            }
        }
        return;
    }

    private void addHLS(final Browser br, final String hls_master) throws Exception {
        if (!this.grabHLS) {
            /* Avoid this http request if user hasn't selected any hls qualities */
            return;
        }
        Browser hlsBR;
        if (br.getURL().contains(".m3u8")) {
            /* HLS master has already been accessed before so no need to access it again. */
            hlsBR = br;
        } else {
            /* Access (hls) master. */
            hlsBR = br.cloneBrowser();
            hlsBR.getPage(hls_master);
        }
        final List<HlsContainer> allHlsContainers = HlsContainer.getHlsQualities(hlsBR);
        for (final HlsContainer hlscontainer : allHlsContainers) {
            if (!hlscontainer.isVideo()) {
                /* Skip audio containers here as we (sometimes) have separate mp3 URLs for this host. */
                continue;
            }
            final String final_download_url = hlscontainer.getDownloadurl();
            addQuality(final_download_url, null, hlscontainer.getBandwidth(), hlscontainer.getWidth(), hlscontainer.getHeight(), foundQualitiesMap);
        }
    }

    /* Especially for video.daserste.de */
    private void addQualityDasersteVideo(final String directurl, final String filesize_str, long bitrate, int width, int height) {
        /* Try to get/Fix correct width/height values. */
        /* Type 1 */
        String width_URL = new Regex(directurl, "(hi|hq|ln|lo|mn)\\.mp4$").getMatch(0);
        if (width_URL == null) {
            /* Type 2 */
            width_URL = new Regex(directurl, "(s|m|sm|ml|l)\\.mp4$").getMatch(0);
        }
        if (width_URL == null) {
            /* Type 3 */
            width_URL = new Regex(directurl, "\\d+((?:_(?:webs|webl))?_ard)\\.mp4$").getMatch(0);
        }
        if (width_URL == null) {
            /* Type 4 */
            width_URL = new Regex(directurl, "/(\\d{1,4})\\-\\d+\\.mp4$").getMatch(0);
        }
        width = getWidth(width_URL, width);
        height = getHeight(width_URL, width, height);
        addQuality(directurl, filesize_str, bitrate, width, height, foundQualitiesMap);
    }

    /* Returns quality identifier String, compatible with quality selection values. Format: protocol_bitrateCorrected_heightCorrected */
    private String getQualityIdentifier(final String directurl, long bitrate, int width, int height) {
        final String protocol;
        if (directurl.contains("m3u8")) {
            protocol = "hls";
        } else {
            protocol = "http";
        }
        /* Use this for quality selection as real resolution can be slightly different than the values which our users can select. */
        final int height_corrected = getHeightForQualitySelection(height);
        final long bitrate_corrected;
        if (bitrate > 0) {
            bitrate_corrected = getBitrateForQualitySelection(bitrate, directurl);
        } else {
            bitrate_corrected = getDefaultBitrateForHeight(height_corrected);
        }
        final String qualityStringForQualitySelection = protocol + "_" + bitrate_corrected + "_" + height_corrected;
        return qualityStringForQualitySelection;
    }

    private DownloadLink addQuality(final String directurl, final String filesize_str, long bitrate, int width, int height, final HashMap<String, DownloadLink> qualitiesMap) {
        /* Errorhandling */
        final String ext;
        if (directurl == null) {
            /* Skip items with bad data. */
            return null;
        } else if (directurl.contains(".mp3")) {
            ext = "mp3";
        } else {
            ext = "mp4";
        }
        long filesize = 0;
        if (filesize_str != null && filesize_str.matches("\\d+")) {
            filesize = Long.parseLong(filesize_str);
        }
        /* Use real resolution inside filenames */
        final String resolution = width + "x" + height;
        final String protocol;
        if (directurl.contains("m3u8")) {
            protocol = "hls";
        } else {
            protocol = "http";
            if (cfg.isGrabBESTEnabled() || cfg.isOnlyBestVideoQualityOfSelectedQualitiesEnabled()) {
                final Browser brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                URLConnectionAdapter con = null;
                try {
                    con = brc.openHeadConnection(directurl);
                    if (!con.isOK() || StringUtils.containsIgnoreCase(con.getContentType(), "text")) {
                        return null;
                    } else {
                        if (con.getLongContentLength() > 0) {
                            filesize = con.getLongContentLength();
                        }
                    }
                } catch (IOException e) {
                    logger.log(e);
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
        }
        final String qualityStringForQualitySelection = getQualityIdentifier(directurl, bitrate, width, height);
        final DownloadLink link = createDownloadlink(directurl.replaceAll("https?://", getHost() + "decrypted://"));
        final MediathekProperties data = link.bindData(MediathekProperties.class);
        data.setTitle(title);
        data.setSourceHost(getHost());
        data.setChannel(this.provider);
        data.setResolution(resolution);
        data.setBitrateTotal(bitrate);
        data.setProtocol(protocol);
        data.setFileExtension(ext);
        if (this.date_timestamp > 0) {
            data.setReleaseDate(this.date_timestamp);
        }
        if (!StringUtils.isEmpty(show)) {
            data.setShow(show);
        }
        link.setFinalFileName(MediathekHelper.getMediathekFilename(link, data, true, true));
        link.setContentUrl(this.parameter);
        if (this.contentID == null) {
            logger.log(new Exception("FixMe!"));
        } else {
            /* Needed for linkid / dupe check! */
            link.setProperty("itemId", this.contentID);
        }
        if (filesize > 0) {
            link.setDownloadSize(filesize);
            link.setAvailable(true);
        } else if (cfg.isFastLinkcheckEnabled()) {
            link.setAvailable(true);
        }
        qualitiesMap.put(qualityStringForQualitySelection, link);
        return link;
    }

    private void handleUserQualitySelection(List<String> selectedQualities) {
        /* We have to re-add the subtitle for the best quality if wished by the user */
        HashMap<String, DownloadLink> finalSelectedQualityMap = new HashMap<String, DownloadLink>();
        if (cfg.isGrabBESTEnabled()) {
            /* User wants BEST only */
            finalSelectedQualityMap = findBESTInsideGivenMap(this.foundQualitiesMap);
        } else {
            final boolean grabUnknownQualities = cfg.isAddUnknownQualitiesEnabled();
            final boolean grab_best_out_of_user_selection = cfg.isOnlyBestVideoQualityOfSelectedQualitiesEnabled();
            boolean atLeastOneSelectedItemExists = false;
            for (final String quality : all_known_qualities) {
                if (selectedQualities.contains(quality) && foundQualitiesMap.containsKey(quality)) {
                    atLeastOneSelectedItemExists = true;
                }
            }
            if (!atLeastOneSelectedItemExists) {
                /* Only logger */
                logger.info("Possible user error: User selected only qualities which are not available --> Adding ALL");
            } else if (selectedQualities.size() == 0) {
                /* Errorhandling for bad user selection */
                logger.info("User selected no quality at all --> Adding ALL qualities instead");
                selectedQualities = all_known_qualities;
            }
            final Iterator<Entry<String, DownloadLink>> it = foundQualitiesMap.entrySet().iterator();
            while (it.hasNext()) {
                final Entry<String, DownloadLink> entry = it.next();
                final String quality = entry.getKey();
                final DownloadLink dl = entry.getValue();
                final boolean isUnknownQuality = !all_known_qualities.contains(quality);
                if (isUnknownQuality) {
                    logger.info("Found unknown quality: " + quality);
                    if (grabUnknownQualities) {
                        logger.info("Adding unknown quality: " + quality);
                        finalSelectedQualityMap.put(quality, dl);
                    }
                } else if (selectedQualities.contains(quality) || !atLeastOneSelectedItemExists) {
                    /* User has selected this particular quality OR we have to add it because user plugin settings were bad! */
                    finalSelectedQualityMap.put(quality, dl);
                }
            }
            /* Check if user maybe only wants the best quality inside his selected videoqualities. */
            if (grab_best_out_of_user_selection) {
                finalSelectedQualityMap = findBESTInsideGivenMap(finalSelectedQualityMap);
            }
        }
        /* Finally add selected URLs */
        final Iterator<Entry<String, DownloadLink>> it = finalSelectedQualityMap.entrySet().iterator();
        while (it.hasNext()) {
            final Entry<String, DownloadLink> entry = it.next();
            final DownloadLink dl = entry.getValue();
            if (cfg.isGrabSubtitleEnabled() && !StringUtils.isEmpty(subtitleLink)) {
                final DownloadLink dl_subtitle = createDownloadlink(subtitleLink.replaceAll("https?://", getHost() + "decrypted://"));
                final MediathekProperties data_src = dl.bindData(MediathekProperties.class);
                final MediathekProperties data_subtitle = dl_subtitle.bindData(MediathekProperties.class);
                data_subtitle.setStreamingType("subtitle");
                data_subtitle.setSourceHost(data_src.getSourceHost());
                data_subtitle.setChannel(data_src.getChannel());
                data_subtitle.setProtocol(data_src.getProtocol() + "sub");
                data_subtitle.setResolution(data_src.getResolution());
                data_subtitle.setBitrateTotal(data_src.getBitrateTotal());
                data_subtitle.setTitle(data_src.getTitle());
                data_subtitle.setFileExtension("xml");
                if (data_src.getShow() != null) {
                    data_subtitle.setShow(data_src.getShow());
                }
                if (data_src.getReleaseDate() > 0) {
                    data_subtitle.setReleaseDate(data_src.getReleaseDate());
                }
                dl_subtitle.setAvailable(true);
                dl_subtitle.setFinalFileName(MediathekHelper.getMediathekFilename(dl_subtitle, data_subtitle, true, true));
                dl_subtitle.setProperty("itemId", dl.getProperty("itemId", null));
                dl_subtitle.setContentUrl(parameter);
                decryptedLinks.add(dl_subtitle);
            }
            decryptedLinks.add(dl);
        }
        if (all_known_qualities.isEmpty()) {
            logger.info("Failed to find any quality at all");
        }
        if (decryptedLinks.size() > 1) {
            FilePackage fp = FilePackage.getInstance();
            if (this.packagename != null) {
                fp.setName(this.packagename);
            } else {
                fp.setName(title);
            }
            fp.addLinks(decryptedLinks);
        }
    }

    private boolean isUnsupportedProtocolDasersteVideo(final String directlink) {
        final boolean isHTTPUrl = directlink == null || !directlink.startsWith("http") || directlink.endsWith("manifest.f4m");
        return isHTTPUrl;
    }

    private HashMap<String, DownloadLink> findBESTInsideGivenMap(final HashMap<String, DownloadLink> map_with_all_qualities) {
        HashMap<String, DownloadLink> newMap = new HashMap<String, DownloadLink>();
        DownloadLink keep = null;
        if (map_with_all_qualities.size() > 0) {
            for (final String quality : all_known_qualities) {
                keep = map_with_all_qualities.get(quality);
                if (keep != null) {
                    newMap.put(quality, keep);
                    break;
                }
            }
        }
        if (newMap.isEmpty()) {
            /* Failover in case of bad user selection or general failure! */
            newMap = map_with_all_qualities;
        }
        return newMap;
    }

    /** Returns videos' width. Do not remove parts of this code without understanding them - this code is crucial for the plugin! */
    private int getWidth(final String width_str, final int width_given) {
        final int width;
        if (width_given > 0) {
            width = width_given;
        } else if (width_str != null) {
            if (width_str.matches("\\d+")) {
                width = Integer.parseInt(width_str);
            } else {
                /* Convert given quality-text to width. */
                if (width_str.equals("mn") || width_str.equals("sm")) {
                    width = 480;
                } else if (width_str.equals("hi") || width_str.equals("m") || width_str.equals("_ard")) {
                    width = 512;
                } else if (width_str.equals("ln") || width_str.equals("ml")) {
                    width = 640;
                } else if (width_str.equals("lo") || width_str.equals("s") || width_str.equals("_webs_ard")) {
                    width = 320;
                } else if (width_str.equals("hq") || width_str.equals("l") || width_str.equals("_webl_ard")) {
                    width = 960;
                } else {
                    width = 0;
                }
            }
        } else {
            /* This should never happen! */
            width = 0;
        }
        return width;
    }

    /** Returns videos' height. Do not remove parts of thise code without understanding them - this code is crucial for the plugin! */
    private int getHeight(final String width_str, final int width, final int height_given) {
        final int height;
        if (height_given > 0) {
            height = height_given;
        } else if (width_str != null) {
            height = Integer.parseInt(convertWidthToHeight(width_str));
        } else {
            /* This should never happen! */
            height = 0;
        }
        return height;
    }

    private String convertWidthToHeight(final String width_str) {
        final String height;
        if (width_str == null) {
            height = "0";
        } else if (width_str.matches("\\d+")) {
            final int width = Integer.parseInt(width_str);
            if (width == 320) {
                height = "180";
            } else if (width == 480) {
                height = "270";
            } else if (width == 512) {
                height = "288";
            } else if (width == 640) {
                height = "360";
            } else if (width == 960) {
                height = "540";
            } else {
                height = Integer.toString(width / 2);
            }
        } else {
            /* Convert given quality-text to height. */
            if (width_str.equals("mn") || width_str.equals("sm")) {
                height = "270";
            } else if (width_str.equals("hi") || width_str.equals("m") || width_str.equals("_ard")) {
                height = "288";
            } else if (width_str.equals("ln") || width_str.equals("ml")) {
                height = "360";
            } else if (width_str.equals("lo") || width_str.equals("s") || width_str.equals("_webs_ard")) {
                height = "180";
            } else if (width_str.equals("hq") || width_str.equals("l") || width_str.equals("_webl_ard")) {
                height = "540";
            } else {
                height = "0";
            }
        }
        return height;
    }

    /* Returns default videoBitrate for width values. */
    private long getDefaultBitrateForHeight(final int height) {
        final String height_str = Integer.toString(height);
        long bitrateVideo;
        if (heigth_to_bitrate.containsKey(height_str)) {
            bitrateVideo = heigth_to_bitrate.get(height_str);
        } else {
            /* Unknown or audio */
            bitrateVideo = 0;
        }
        return bitrateVideo;
    }

    /**
     * Given width may not always be exactly what we have in our quality selection but we need an exact value to make the user selection
     * work properly!
     */
    private int getHeightForQualitySelection(final int height) {
        final int heightelect;
        if (height > 0 && height <= 250) {
            heightelect = 180;
        } else if (height > 250 && height <= 272) {
            heightelect = 270;
        } else if (height > 272 && height <= 320) {
            heightelect = 280;
        } else if (height > 320 && height <= 400) {
            heightelect = 360;
        } else if (height > 400 && height < 576) {
            heightelect = 540;
        } else if (height >= 576 && height <= 600) {
            heightelect = 576;
        } else if (height > 600 && height <= 800) {
            heightelect = 720;
        } else {
            /* Either unknown quality or audio (0x0) */
            heightelect = height;
        }
        return heightelect;
    }

    /**
     * Given bandwidth may not always be exactly what we have in our quality selection but we need an exact value to make the user selection
     * work properly!
     */
    private long getBitrateForQualitySelection(final long bandwidth, final String directurl) {
        final long bandwidthselect;
        if (directurl != null && directurl.contains(".mp3")) {
            /* Audio --> There is usually only 1 bandwidth available so for our selection, we use the value 0. */
            bandwidthselect = 0;
        } else if (bandwidth > 0 && bandwidth <= 250000) {
            bandwidthselect = 189000;
        } else if (bandwidth > 250000 && bandwidth <= 350000) {
            /* lower 270 */
            bandwidthselect = 317000;
        } else if (bandwidth > 350000 && bandwidth <= 480000) {
            /* higher/normal 270 */
            bandwidthselect = 448000;
        } else if (bandwidth > 480000 && bandwidth <= 800000) {
            /* 280 */
            bandwidthselect = 605000;
        } else if (bandwidth > 800000 && bandwidth <= 1600000) {
            /* 360 */
            bandwidthselect = 1213000;
        } else if (bandwidth > 1600000 && bandwidth <= 2800000) {
            /* 540 */
            bandwidthselect = 1989000;
        } else if (bandwidth > 2800000 && bandwidth <= 4500000) {
            /* 720 */
            bandwidthselect = 3773000;
        } else if (bandwidth > 4500000 && bandwidth <= 10000000) {
            /* 1080 */
            bandwidthselect = 6666000;
        } else {
            /* Probably unknown quality */
            bandwidthselect = bandwidth;
        }
        return bandwidthselect;
    }

    // private String getXML(final String parameter) {
    // return getXML(this.br.toString(), parameter);
    // }
    private String getXML(final String source, final String parameter) {
        return new Regex(source, "<" + parameter + "[^<]*?>([^<>]*?)</" + parameter + ">").getMatch(0);
    }

    public static final String correctRegionString(final String input) {
        String output;
        if (input.equals("de")) {
            output = "de";
        } else {
            output = "weltweit";
        }
        return output;
    }

    private long getDateMilliseconds(String input) {
        if (input == null) {
            return -1;
        }
        final long date_milliseconds;
        if (input.matches("\\d{4}\\-\\d{2}\\-\\d{2}")) {
            date_milliseconds = TimeFormatter.getMilliSeconds(input, "yyyy-MM-dd", Locale.GERMAN);
        } else if (input.matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}")) {
            date_milliseconds = TimeFormatter.getMilliSeconds(input, "dd.MM.yyyy HH:mm", Locale.GERMAN);
        } else {
            /* 2015-06-23T20:15:00.000+02:00 --> 2015-06-23T20:15:00.000+0200 */
            input = new Regex(input, "^(\\d{4}\\-\\d{2}\\-\\d{2})").getMatch(0);
            date_milliseconds = TimeFormatter.getMilliSeconds(input, "yyyy-MM-dd", Locale.GERMAN);
        }
        return date_milliseconds;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}