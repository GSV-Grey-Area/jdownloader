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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.downloader.hls.M3U8Playlist;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.ZdfDeMediathek.ZdfmediathekConfigInterface;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "zdf.de", "3sat.de" }, urls = { "https?://(?:www\\.)?zdf\\.de/.+/[A-Za-z0-9_\\-]+\\.html|https?://(?:www\\.)?zdf\\.de/uri/(?:syncvideoimport_beitrag_\\d+|transfer_SCMS_[a-f0-9\\-]+|[a-z0-9\\-]+)", "https?://(?:www\\.)?3sat\\.de/.+/[A-Za-z0-9_\\-]+\\.html|https?://(?:www\\.)?3sat\\.de/uri/(?:syncvideoimport_beitrag_\\d+|transfer_SCMS_[a-f0-9\\-]+|[a-z0-9\\-]+)" })
public class ZDFMediathekDecrypter extends PluginForDecrypt {
    ArrayList<DownloadLink> decryptedLinks     = new ArrayList<DownloadLink>();
    private String          PARAMETER          = null;
    private String          PARAMETER_ORIGINAL = null;
    private String          url_subtitle       = null;
    private boolean         fastlinkcheck      = false;
    private boolean         grabBest           = false;
    private boolean         grabSubtitles      = false;
    private long            filesizeSubtitle   = 0;
    private final String    TYPE_ZDF           = "https?://(?:www\\.)?(?:zdf\\.de|3sat\\.de)/.+";
    /* Not sure where these URLs come from. Probably old RSS readers via old APIs ... */
    private final String    TYPER_ZDF_REDIRECT = "https?://[^/]+/uri/.+";
    /* Important: Keep this updated & keep this in order: Highest --> Lowest */
    private List<String>    all_known_qualities;

    public ZDFMediathekDecrypter(final PluginWrapper wrapper) {
        super(wrapper);
    }

    private List<String> getKnownQualities() {
        /* Old static Array */
        // private final List<String> all_known_qualities = Arrays.asList("hls_mp4_720", "http_mp4_hd", "http_webm_hd", "hls_mp4_480",
        // "http_mp4_veryhigh", "http_webm_veryhigh", "hls_mp4_360", "http_webm_high", "hls_mp4_270", "http_mp4_high", "http_webm_low",
        // "hls_mp4_170", "http_mp4_low", "hls_aac_0");
        final List<String> all_known_qualities = new ArrayList<String>();
        final String[] knownProtocols = { "http", "hls" };
        final String[] knownExtensions = { "mp4", "webm" };
        final String[] knownQualityNames = { "1080", "hd", "veryhigh", "720", "480", "360", "high", "low", "170" };
        final String[] knownAudioClasses = { "main", "ad" };
        for (final String protocol : knownProtocols) {
            for (final String extension : knownExtensions) {
                for (final String qualityName : knownQualityNames) {
                    for (final String audioClass : knownAudioClasses) {
                        final String qualityIdentifier = protocol + "_" + extension + "_" + qualityName + "_" + audioClass;
                        logger.info("Possible quality: qualityIdentifier: " + qualityIdentifier);
                        all_known_qualities.add(qualityIdentifier);
                    }
                }
            }
        }
        /* Add all possible audio versions */
        for (final String audioClass : knownAudioClasses) {
            all_known_qualities.add("hls_aac_0_" + audioClass);
        }
        return all_known_qualities;
    }

    /** Example of a podcast-URL: http://www.zdf.de/ZDFmediathek/podcast/1074856?view=podcast */
    /** Related sites: see RegExes, and also: 3sat.de */
    @SuppressWarnings({ "deprecation" })
    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, final ProgressController progress) throws Exception {
        this.br.setAllowedResponseCodes(new int[] { 500 });
        PARAMETER = param.toString();
        PARAMETER_ORIGINAL = param.toString();
        setBrowserExclusive();
        br.setFollowRedirects(true);
        all_known_qualities = getKnownQualities();
        getDownloadLinksZdfNew();
        if (decryptedLinks == null) {
            logger.warning("Decrypter out of date for link: " + PARAMETER);
            return null;
        }
        return decryptedLinks;
    }

    protected DownloadLink createDownloadlink(final String url) {
        final DownloadLink dl = super.createDownloadlink(url.replaceAll("https?://", "decryptedmediathek://"));
        if (this.fastlinkcheck) {
            dl.setAvailable(true);
        }
        return dl;
    }

    /** Do not delete this code! This can crawl embedded ZDF IDs! */
    // private void crawlEmbeddedUrlsHeute() throws Exception {
    // br.getPage(this.PARAMETER);
    // if (br.containsHTML("Der Beitrag konnte nicht gefunden werden") || this.br.getHttpConnection().getResponseCode() == 404 ||
    // this.br.getHttpConnection().getResponseCode() == 500) {
    // decryptedLinks.add(this.createOfflinelink(PARAMETER_ORIGINAL));
    // return;
    // }
    // final String[] ids = this.br.getRegex("\"videoId\"\\s*:\\s*\"([^\"]*?)\"").getColumn(0);
    // for (final String videoid : ids) {
    // /* These urls go back into the decrypter. */
    // final String mainlink = "https://www." + this.getHost() + "/nachrichten/heute-journal/" + videoid + ".html";
    // decryptedLinks.add(super.createDownloadlink(mainlink));
    // }
    // return;
    // }
    private void crawlEmbeddedUrlsZdfNew() throws IOException {
        this.br.getPage(this.PARAMETER);
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            this.decryptedLinks.add(this.createOfflinelink(this.PARAMETER));
            return;
        }
        final String[] embedded_player_ids = this.br.getRegex("data\\-zdfplayer\\-id=\"([^<>\"]+)\"").getColumn(0);
        for (final String embedded_player_id : embedded_player_ids) {
            final String finallink = String.format("https://www.zdf.de/jdl/jdl/%s.html", embedded_player_id);
            this.decryptedLinks.add(super.createDownloadlink(finallink));
        }
    }

    /** Returns API parameters from html. */
    private String[] getApiParams(Browser br, final String url, final boolean returnHardcodedData) throws IOException {
        String apitoken;
        /* 2020-03-19: apitoken2 not required anymore?! */
        String apitoken2;
        String api_base;
        String profile;
        if (url == null) {
            return null;
        } else if (returnHardcodedData) {
            if (url.contains("3sat.de")) {
                /* 3sat.de */
                /* 2019-11-29 */
                apitoken = "22918a9c7a733c027addbcc7d065d4349d375825";
                apitoken2 = "13e717ac1ff5c811c72844cebd11fc59ecb8bc03";
                api_base = "https://api.3sat.de";
                /* 2020-03-31 */
                profile = "player2";
            } else {
                /* zdf.de / heute.de and so on */
                /* 2020-03-19 */
                apitoken = "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJOVDdrVWFTanNzNTZkaTZ5UjViQk9nbkpqZWw5QXQ1UGszX2JGcVRTMzFJIn0.eyJqdGkiOiJmYzlhZTA1Yi0wYmJhLTQ1YjMtOTU5Mi04YTIzMTAyNTg1Y2YiLCJleHAiOjE1ODUwNjc3MTYsIm5iZiI6MCwiaWF0IjoxNTg0NDYyOTE2LCJpc3MiOiJodHRwczovL3Nzby1yaC1zc28uYXBwcy5vcGVuc2hpZnQuemRmLmRlL2F1dGgvcmVhbG1zLzNzY2FsZSIsImF1ZCI6ImFjY291bnQiLCJzdWIiOiJhY2RmZTZkYy03NGZhLTRlODAtOTNjNC1iZjNlZDNiYzFkMTQiLCJ0eXAiOiJCZWFyZXIiLCJhenAiOiIxMjhkNzI2ZSIsImF1dGhfdGltZSI6MCwic2Vzc2lvbl9zdGF0ZSI6IjUyNzlhNGRjLTc0ODItNGJkZS1iOTlhLWIzZTNjZTdiMDNmNCIsImFjciI6IjEiLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsib2ZmbGluZV9hY2Nlc3MiLCJ1bWFfYXV0aG9yaXphdGlvbiJdfSwicmVzb3VyY2VfYWNjZXNzIjp7ImFjY291bnQiOnsicm9sZXMiOlsibWFuYWdlLWFjY291bnQiLCJtYW5hZ2UtYWNjb3VudC1saW5rcyIsInZpZXctcHJvZmlsZSJdfX0sInNjb3BlIjoicHJvZmlsZSBlbWFpbCIsImVtYWlsX3ZlcmlmaWVkIjpmYWxzZSwiY2xpZW50SG9zdCI6IjE3Mi4yMy43NS4yNDQiLCJjbGllbnRJZCI6IjEyOGQ3MjZlIiwicHJlZmVycmVkX3VzZXJuYW1lIjoic2VydmljZS1hY2NvdW50LTEyOGQ3MjZlIiwiY2xpZW50QWRkcmVzcyI6IjE3Mi4yMy43NS4yNDQiLCJlbWFpbCI6InNlcnZpY2UtYWNjb3VudC0xMjhkNzI2ZUBwbGFjZWhvbGRlci5vcmcifQ.j2ssjrzdAhyOr54D18oYvhb-0LR0tViQZyIwIa9KN40h6pG1E32Dmiu2Rf_6lGNLl4uRO0I-CRIKti9HyIZSNHuH_3Gia5PqHLzWaVhapePMC_weuqdY5qJ5UkZQhD_zO5CFhxruRv7-Bhw3QEG5l8RSuN0Chh9YfD2MKZkpgHdMBO52m4rDSTKsxDuYGBIQlk_gaf3mUQiBdHoxZGTyf4yL9evy8rQUiCa4NgDsBBKaTDbc9cOwsMDlYsWLuqaStWIdJj9PYXjtjBlmCPhJgqO-c_DlFyjGCi2u0-BxMZP15iDGquAPa58LJywHWj-xajcmkN6Q9yDvo_P0zlIjzg";
                apitoken2 = null;
                api_base = "https://api.zdf.de";
                /* 2020-03-31 */
                profile = "player-3";
            }
            return new String[] { apitoken, apitoken2, api_base, profile };
        } else {
            final Browser brc;
            if (br == null) {
                brc = this.br;
            } else {
                brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                brc.getPage(url);
            }
            apitoken = brc.getRegex("\"apiToken\"\\s*:\\s*\"([^\"\\']+)\"").getMatch(0);
            apitoken2 = null;
            api_base = brc.getRegex("apiService\\s*:\\s*'(https?://[^<>\"\\']+)'").getMatch(0);
            profile = brc.getRegex("\\.json\\?profile=([^\"]+)\"").getMatch(0);
            if (apitoken == null || api_base == null || profile == null) {
                return null;
            }
            return new String[] { apitoken, apitoken2, api_base, profile };
        }
    }

    @SuppressWarnings({ "unchecked" })
    private void getDownloadLinksZdfNew() throws Exception {
        List<String> all_selected_qualities = new ArrayList<String>();
        final List<String> all_found_languages = new ArrayList<String>();
        final HashMap<String, DownloadLink> all_found_downloadlinks = new HashMap<String, DownloadLink>();
        final ZdfmediathekConfigInterface cfg = PluginJsonConfig.get(jd.plugins.hoster.ZdfDeMediathek.ZdfmediathekConfigInterface.class);
        grabBest = cfg.isGrabBESTEnabled();
        fastlinkcheck = cfg.isFastLinkcheckEnabled();
        grabSubtitles = cfg.isGrabSubtitleEnabled();
        final boolean grabHlsAudio = cfg.isGrabAudio();
        final boolean grabAudioDeskription = cfg.isGrabAudioDeskription();
        if (grabHlsAudio) {
            all_selected_qualities.add("hls_aac_0");
        }
        final boolean grabHls170 = cfg.isGrabHLS170pVideoEnabled();
        final boolean grabHls270 = cfg.isGrabHLS270pVideoEnabled();
        final boolean grabHls360 = cfg.isGrabHLS360pVideoEnabled();
        final boolean grabHls480 = cfg.isGrabHLS480pVideoEnabled();
        final boolean grabHls570 = cfg.isGrabHLS570pVideoEnabled();
        final boolean grabHls720 = cfg.isGrabHLS720pVideoEnabled();
        if (grabHls170) {
            all_selected_qualities.add("hls_mp4_170");
        }
        if (grabHls270) {
            all_selected_qualities.add("hls_mp4_270");
        }
        if (grabHls360) {
            all_selected_qualities.add("hls_mp4_360");
        }
        if (grabHls480) {
            all_selected_qualities.add("hls_mp4_480");
        }
        if (grabHls570) {
            all_selected_qualities.add("hls_mp4_570");
        }
        if (grabHls720) {
            all_selected_qualities.add("hls_mp4_720");
        }
        final boolean grabHttpMp4Low = cfg.isGrabHTTPMp4_170pVideoEnabled();
        final boolean grabHttpMp4High = cfg.isGrabHTTPMp4_270pVideoEnabled();
        final boolean grabHttpMp4VeryHigh = cfg.isGrabHTTPMp4_480pVideoEnabled();
        final boolean grabHttpMp4HD = cfg.isGrabHTTPMp4HDVideoEnabled();
        if (grabHttpMp4Low) {
            all_selected_qualities.add("http_mp4_low");
        }
        if (grabHttpMp4High) {
            all_selected_qualities.add("http_mp4_high");
        }
        if (grabHttpMp4VeryHigh) {
            all_selected_qualities.add("http_mp4_veryhigh");
        }
        if (grabHttpMp4HD) {
            all_selected_qualities.add("http_mp4_hd");
        }
        final boolean grabHttpWebmLow = cfg.isGrabHTTPWebmLowVideoEnabled();
        final boolean grabHttpWebmHigh = cfg.isGrabHTTPWebmHighVideoEnabled();
        final boolean grabHttpWebmVeryHigh = cfg.isGrabHTTPWebmVeryHighVideoEnabled();
        final boolean grabHttpWebmHD = cfg.isGrabHTTPWebmHDVideoEnabled();
        if (grabHttpWebmLow) {
            all_selected_qualities.add("http_webm_low");
        }
        if (grabHttpWebmHigh) {
            all_selected_qualities.add("http_webm_high");
        }
        if (grabHttpWebmVeryHigh) {
            all_selected_qualities.add("http_webm_veryhigh");
        }
        if (grabHttpWebmHD) {
            all_selected_qualities.add("http_webm_hd");
        }
        final boolean user_selected_nothing = all_selected_qualities.size() == 0;
        if (user_selected_nothing) {
            logger.info("User selected no quality at all --> Adding ALL qualities instead");
            all_selected_qualities = all_known_qualities;
        }
        /*
         * Grabbing hls means we make an extra http request --> Only do this if wished by the user or if the user set bad plugin settings!
         */
        final boolean grabHLS = grabHlsAudio || grabHls170 || grabHls270 || grabHls360 || grabHls480 || grabHls570 || grabHls720 || user_selected_nothing;
        /*
         * 2017-02-08: The only thing download has and http stream has not == http veryhigh --> Only grab this if user has selected it
         * explicitly!
         */
        boolean grabDownloadUrls = !grabBest && grabHttpMp4HD;
        final String sophoraIDSource;
        if (this.PARAMETER.matches(TYPER_ZDF_REDIRECT)) {
            this.br.setFollowRedirects(false);
            this.br.getPage(this.PARAMETER);
            sophoraIDSource = this.br.getRedirectLocation();
            this.br.setFollowRedirects(true);
        } else {
            sophoraIDSource = this.PARAMETER;
        }
        final String sophoraID = new Regex(sophoraIDSource, "/([^/]+)\\.html").getMatch(0);
        if (sophoraID == null) {
            /* Probably no videocontent - most likely, used added an invalid TYPER_ZDF_REDIRECT url. */
            decryptedLinks.add(this.createOfflinelink(PARAMETER));
            return;
        }
        final String apiParams[] = getApiParams(br, PARAMETER_ORIGINAL, false);
        /* 2016-12-21: By hardcoding the apitoken we can save one http request thus have a faster crawl process :) */
        this.br.getHeaders().put("Api-Auth", "Bearer " + apiParams[0]);
        this.br.getPage(apiParams[2] + "/content/documents/" + sophoraID + ".json?profile=" + apiParams[3]);
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(PARAMETER));
            return;
        }
        LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaMap(this.br.toString());
        LinkedHashMap<String, Object> entries_2 = null;
        final String contentType = (String) entries.get("contentType");
        String title = (String) entries.get("title");
        if (StringUtils.isEmpty(title)) {
            /* Fallback */
            title = sophoraID;
        }
        final String editorialDate = (String) entries.get("editorialDate");
        final Object tvStationo = entries.get("tvService");
        final String tv_station = tvStationo != null && tvStationo instanceof String ? (String) tvStationo : "ZDF";
        // final Object hasVideoo = entries.get("hasVideo");
        // final boolean hasVideo = hasVideoo != null && hasVideoo instanceof Boolean ? ((Boolean) entries.get("hasVideo")).booleanValue() :
        // false;
        entries_2 = (LinkedHashMap<String, Object>) entries.get("http://zdf.de/rels/brand");
        final String tv_show = entries_2 != null ? (String) entries_2.get("title") : null;
        entries_2 = (LinkedHashMap<String, Object>) entries.get("mainVideoContent");
        if (entries_2 == null) {
            /* Not a single video? Maybe we have a playlist / embedded video(s)! */
            logger.info("Content is not a video --> Scanning html for embedded content");
            crawlEmbeddedUrlsZdfNew();
            if (this.decryptedLinks.size() == 0) {
                this.decryptedLinks.add(this.createOfflinelink(this.PARAMETER_ORIGINAL, "NO_DOWNLOADABLE_CONTENT"));
            }
            return;
        }
        entries_2 = (LinkedHashMap<String, Object>) entries_2.get("http://zdf.de/rels/target");
        final String player_url_template = (String) entries_2.get("http://zdf.de/rels/streams/ptmd-template");
        String internal_videoid = (String) JavaScriptEngineFactory.walkJson(entries_2, "streams/default/extId");
        if (StringUtils.isEmpty(player_url_template)) {
            this.decryptedLinks.add(this.createOfflinelink(this.PARAMETER_ORIGINAL, "NO_DOWNLOADABLE_CONTENT"));
            return;
        }
        /* 2017-02-03: Not required at the moment */
        // if (!hasVideo) {
        // logger.info("Content is not a video --> Nothing to download");
        // return ret;
        // }
        if (inValidate(contentType) || inValidate(editorialDate) || inValidate(tv_station)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /* Show is not always available - merge it with the title, if tvShow is available. */
        if (tv_show != null) {
            title = tv_show + " - " + title;
        }
        String base_title = title;
        final String date_formatted = new Regex(editorialDate, "(\\d{4}\\-\\d{2}\\-\\d{2})").getMatch(0);
        if (StringUtils.isEmpty(internal_videoid)) {
            internal_videoid = new Regex(player_url_template, "/([^/]{2,})$").getMatch(0);
        }
        if (date_formatted == null || internal_videoid == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String filename_packagename_base_title = date_formatted + "_" + tv_station + "_" + base_title;
        short counter = 0;
        short highestHlsMasterValue = 0;
        short hlsMasterValueTemp = 0;
        int highestHlsBandwidth = 0;
        boolean finished = false;
        boolean grabDownloadUrlsPossible = false;
        DownloadLink highestHlsDownload = null;
        do {
            if (this.isAbort()) {
                return;
            }
            if (counter == 0) {
                /* Stream download */
                accessPlayerJson(apiParams[2], player_url_template, "ngplayer_2_3");
            } else if (grabDownloadUrls && grabDownloadUrlsPossible) {
                /* Official video download */
                accessPlayerJson(apiParams[2], player_url_template, "zdf_pd_download_1");
                finished = true;
            } else {
                /* Fail safe && case when there are no additional downloadlinks available. */
                finished = true;
                break;
            }
            entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaMap(this.br.toString());
            final Object downloadAllowed_o = JavaScriptEngineFactory.walkJson(entries, "attributes/downloadAllowed/value");
            if (downloadAllowed_o != null && downloadAllowed_o instanceof Boolean) {
                /* Usually this is set in the first loop to decide whether a 2nd loop is required. */
                grabDownloadUrlsPossible = ((Boolean) downloadAllowed_o).booleanValue();
            }
            final List<String> hlsDupeArray = new ArrayList<String>();
            final ArrayList<Object> priorityList = (ArrayList<Object>) entries.get("priorityList");
            final Object captions = JavaScriptEngineFactory.walkJson(entries, "captions");
            if (grabSubtitles && (captions instanceof List) && ((List<?>) captions).size() > 0) {
                // "captions" : []
                /* Captions may be available in different versions */
                final List<Object> subtitlesO = (List<Object>) entries.get("captions");
                String subtitleOriginal = null;
                String subtitleForDisabledPeople = null;
                Map<String, Object> subInfo = null;
                for (final Object subtitleO : subtitlesO) {
                    subInfo = (Map<String, Object>) subtitleO;
                    final String subtitleType = (String) subInfo.get("class");
                    final String uri = (String) subInfo.get("uri");
                    final boolean formatIsSupported = uri != null && uri.toLowerCase(Locale.ENGLISH).contains(".xml");
                    /* E.g. "ebu-tt-d-basic-de" or "webvtt" */
                    // final String format = (String) subInfo.get("format");
                    /* Skip unsupported formats */
                    if (!formatIsSupported) {
                        continue;
                    } else if (subtitleType.equalsIgnoreCase("omu")) {
                        subtitleOriginal = uri;
                    } else if (subtitleType.equalsIgnoreCase("hoh")) {
                        subtitleForDisabledPeople = uri;
                    } else {
                        logger.warning("Unsupported subtitle type: " + subtitleType);
                    }
                }
                // final Object subtitleO = JavaScriptEngineFactory.walkJson(entries, "captions/{0}/uri");
                // url_subtitle = subtitleO != null ? (String) subtitleO : null;
                if (!StringUtils.isEmpty(subtitleOriginal)) {
                    this.url_subtitle = subtitleOriginal;
                    /*
                     * Grab the filesize here once so if the user adds many links, JD will not check the same subtitle URL multiple times.
                     */
                    URLConnectionAdapter con = null;
                    try {
                        con = this.br.openHeadConnection(this.url_subtitle);
                        if (con.isOK() && !con.getContentType().contains("html")) {
                            filesizeSubtitle = con.getLongContentLength();
                        }
                    } finally {
                        if (con != null) {
                            try {
                                con.disconnect();
                            } catch (final Throwable e) {
                            }
                        }
                    }
                }
            }
            for (final Object priority_o : priorityList) {
                entries = (LinkedHashMap<String, Object>) priority_o;
                final ArrayList<Object> formitaeten = (ArrayList<Object>) entries.get("formitaeten");
                for (final Object formitaet_o : formitaeten) {
                    /* 2020-12-21: Skips (two) lower http qualities - just a test */
                    // final String facet = (String) JavaScriptEngineFactory.walkJson(entries, "facets/{0}");
                    // if ("restriction_useragent".equalsIgnoreCase(facet)) {
                    // continue;
                    // }
                    entries = (LinkedHashMap<String, Object>) formitaet_o;
                    final boolean isAdaptive = ((Boolean) entries.get("isAdaptive")).booleanValue();
                    final String type = (String) entries.get("type");
                    String protocol = "http";
                    if (isAdaptive && !type.contains("m3u8")) {
                        /* 2017-02-03: Skip HDS as HLS already contains all segment quelities. */
                        continue;
                    } else if (isAdaptive) {
                        protocol = "hls";
                    }
                    String ext;
                    if (type.contains("vorbis")) {
                        /* http webm streams. */
                        ext = "webm";
                    } else {
                        /* http mp4- and segment streams. */
                        ext = "mp4";
                    }
                    if (isAdaptive && !grabHLS) {
                        /* Skip hls if not required by the user. */
                        continue;
                    }
                    final ArrayList<Object> qualities = (ArrayList<Object>) entries.get("qualities");
                    for (final Object qualities_o : qualities) {
                        entries = (LinkedHashMap<String, Object>) qualities_o;
                        final String quality = (String) entries.get("quality");
                        if (inValidate(quality)) {
                            /* Skip invalid items */
                            continue;
                        }
                        entries = (LinkedHashMap<String, Object>) entries.get("audio");
                        final ArrayList<Object> tracks = (ArrayList<Object>) entries.get("tracks");
                        for (final Object tracks_o : tracks) {
                            entries = (LinkedHashMap<String, Object>) tracks_o;
                            final String cdn = (String) entries.get("cdn");
                            /* E.g. 'main' = normal, 'ad' = 'audio deskription'(Audio commentary with background information) */
                            final String audio_class = (String) entries.get("class");
                            final String language = (String) entries.get("language");
                            final long filesize = JavaScriptEngineFactory.toLong(entries.get("filesize"), 0);
                            String uri = (String) entries.get("uri");
                            if (inValidate(cdn) || inValidate(audio_class) || inValidate(language) || inValidate(uri)) {
                                /* Skip invalid objects */
                                continue;
                            } else if (audio_class.equals("ad") && !grabAudioDeskription) {
                                logger.info("Skipping Audiodeskription");
                                continue;
                            }
                            final String audio_class_user_readable = convertInternalAudioClassToUserReadable(audio_class);
                            String final_download_url;
                            String linkid;
                            String final_filename;
                            /* internal_videoid, type, cdn, language, audio_class, protocol, resolution */
                            final String linkid_format = "%s_%s_%s_%s_%s_%s_%s";
                            /* filename_packagename_base_title, protocol, resolution, language, audio_class_user_readable, ext */
                            final String final_filename_format = "%s_%s_%s_%s_%s.%s";
                            DownloadLink dl;
                            if (isAdaptive) {
                                /* HLS Segment download */
                                String hls_master_quality_str = new Regex(uri, "m3u8/(\\d+)/").getMatch(0);
                                if (hls_master_quality_str == null) {
                                    // we asume this leads to m3u8 with multiple qualities
                                    // better than not processing any m3u8
                                    hls_master_quality_str = String.valueOf(Short.MAX_VALUE);
                                }
                                final String hls_master_dupe_string = hls_master_quality_str + "_" + audio_class;
                                if (hlsDupeArray.contains(hls_master_dupe_string)) {
                                    /* Skip dupes */
                                    continue;
                                }
                                hlsDupeArray.add(hls_master_dupe_string);
                                /* Access (hls) master. */
                                this.br.getPage(uri);
                                final List<HlsContainer> allHlsContainers = HlsContainer.getHlsQualities(this.br);
                                long duration = -1;
                                for (final HlsContainer hlscontainer : allHlsContainers) {
                                    if (duration == -1) {
                                        duration = 0;
                                        final List<M3U8Playlist> playList = hlscontainer.getM3U8(br.cloneBrowser());
                                        if (playList != null) {
                                            for (M3U8Playlist play : playList) {
                                                duration += play.getEstimatedDuration();
                                            }
                                        }
                                    }
                                    final String height_for_quality_selection = getHeightForQualitySelection(hlscontainer.getHeight());
                                    final String resolution = hlscontainer.getResolution();
                                    final_download_url = hlscontainer.getDownloadurl();
                                    ext = hlscontainer.getFileExtension().replace(".", "");
                                    linkid = this.getHost() + "://" + String.format(linkid_format, internal_videoid, type, cdn, language, audio_class, protocol, resolution);
                                    final_filename = encodeUnicode(String.format(final_filename_format, filename_packagename_base_title, protocol, resolution, language, audio_class_user_readable, ext));
                                    dl = createDownloadlink(final_download_url);
                                    if (hlscontainer.getBandwidth() > highestHlsBandwidth) {
                                        /*
                                         * While adding the URLs, let's find the BEST quality url. In case we need it later we will already
                                         * know which one is the BEST.
                                         */
                                        highestHlsBandwidth = hlscontainer.getBandwidth();
                                        highestHlsDownload = dl;
                                    }
                                    setDownloadlinkProperties(dl, final_filename, type, linkid, title, tv_show, date_formatted, tv_station);
                                    dl.setProperty("hlsBandwidth", hlscontainer.getBandwidth());
                                    if (duration > 0 && hlscontainer.getBandwidth() > 0) {
                                        dl.setDownloadSize(duration / 1000 * hlscontainer.getBandwidth() / 8);
                                    }
                                    all_found_downloadlinks.put(generateQualitySelectorString(protocol, ext, height_for_quality_selection, language, audio_class, all_found_languages), dl);
                                }
                                /* Set this so we do not crawl this particular hls master again next round. */
                                highestHlsMasterValue = hlsMasterValueTemp;
                            } else {
                                /* http download */
                                final_download_url = uri;
                                /*
                                 * 2020-12-21: Some tests: There are higher http qualities available than what we get via API (see also
                                 * mediathekview) ...
                                 */
                                /* Do NOT alter official downloadurls such as "http://downloadzdf-a.akamaihd.net/..." */
                                if (final_download_url.matches("https?://rodlzdf-a\\.akamaihd\\.net/.+_\\d+k_p\\d+v\\d+\\.mp4")) {
                                    /* Improve "veryhigh" */
                                    final_download_url = final_download_url.replace("_1628k_p13v15.mp4", "_3360k_p36v15.mp4");
                                    /* Improve "high/medium" */
                                    final_download_url = final_download_url.replace("_808k_p11v15.mp4", "_2360k_p35v15.mp4");
                                    /* Improve "low" */
                                    final_download_url = final_download_url.replace("_508k_p9v15.mp4", "_808k_p11v15.mp4");
                                }
                                linkid = this.getHost() + "://" + String.format(linkid_format, internal_videoid, type, cdn, language, audio_class, protocol, quality);
                                final_filename = encodeUnicode(String.format(final_filename_format, filename_packagename_base_title, protocol, quality, language, audio_class_user_readable, ext));
                                dl = createDownloadlink(final_download_url);
                                /* Usually the filesize is only given for the official downloads. */
                                if (filesize > 0) {
                                    dl.setAvailable(true);
                                    dl.setDownloadSize(filesize);
                                }
                                setDownloadlinkProperties(dl, final_filename, type, linkid, title, tv_show, date_formatted, tv_station);
                                all_found_downloadlinks.put(generateQualitySelectorString(protocol, ext, quality, language, audio_class, all_found_languages), dl);
                            }
                        }
                        /** Extra abort handling within here to abort hls crawling as it also needs one http request for each quality. */
                        if (this.isAbort()) {
                            return;
                        }
                    }
                }
            }
            counter++;
        } while (!finished);
        /* Finally, check which qualities the user actually wants to have. */
        if (this.grabBest && highestHlsDownload != null) {
            /* Best is easy and even if it was an unknown quality, we knew that highest hls == always BEST! */
            addDownloadLink(highestHlsDownload);
        } else {
            boolean atLeastOneSelectedItemExists = false;
            final boolean grabUnknownQualities = cfg.isAddUnknownQualitiesEnabled();
            HashMap<String, DownloadLink> all_selected_downloadlinks = new HashMap<String, DownloadLink>();
            final Iterator<Entry<String, DownloadLink>> iterator_all_found_downloadlinks = all_found_downloadlinks.entrySet().iterator();
            while (iterator_all_found_downloadlinks.hasNext()) {
                final Entry<String, DownloadLink> dl_entry = iterator_all_found_downloadlinks.next();
                final String dl_quality_string = dl_entry.getKey();
                if (containsQuality(dl_quality_string, all_selected_qualities)) {
                    atLeastOneSelectedItemExists = true;
                    all_selected_downloadlinks.put(dl_quality_string, dl_entry.getValue());
                } else if (!containsQuality(dl_quality_string, all_known_qualities) && grabUnknownQualities) {
                    logger.info("Found unknown quality: " + dl_quality_string);
                    if (grabUnknownQualities) {
                        logger.info("Adding unknown quality: " + dl_quality_string);
                        all_selected_downloadlinks.put(dl_quality_string, dl_entry.getValue());
                    }
                }
            }
            if (!atLeastOneSelectedItemExists) {
                logger.info("Possible user error: User selected only qualities which are not available --> Adding ALL");
                while (iterator_all_found_downloadlinks.hasNext()) {
                    final Entry<String, DownloadLink> dl_entry = iterator_all_found_downloadlinks.next();
                    decryptedLinks.add(dl_entry.getValue());
                }
            } else {
                if (cfg.isOnlyBestVideoQualityOfSelectedQualitiesEnabled()) {
                    all_selected_downloadlinks = findBESTInsideGivenMap(all_selected_downloadlinks);
                }
                /* Finally add selected URLs */
                final Iterator<Entry<String, DownloadLink>> it = all_selected_downloadlinks.entrySet().iterator();
                while (it.hasNext()) {
                    final Entry<String, DownloadLink> entry = it.next();
                    final DownloadLink dl = entry.getValue();
                    addDownloadLink(dl);
                }
            }
        }
        if (all_found_downloadlinks.isEmpty()) {
            logger.info("Failed to find any quality at all");
        }
        if (decryptedLinks.size() > 1) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(filename_packagename_base_title);
            fp.addLinks(decryptedLinks);
        }
    }

    private String generateQualitySelectorString(final String protocol, final String ext, final String quality, final String language, final String audio_class, final List<String> all_found_languages) {
        /* protocol, ext, quality, audio_class */
        final String quality_selector_format = "%s_%s_%s_%s";
        String quality_selector_string = String.format(quality_selector_format, protocol, ext, quality, audio_class);
        if (!all_found_languages.contains(language)) {
            /* TODO: Improve this handling! */
            all_found_languages.add(language);
            if (all_found_languages.size() > 1) {
                /*
                 * Check for multiple languages - this will break quality selection but is a rare case and important to handle because if we
                 * don't handle this, we have the same quality_selector values for different versions!!
                 */
                quality_selector_string += "_" + language;
            }
        }
        return quality_selector_string;
    }

    private boolean containsQuality(String qualityID, List<String> qualities) {
        for (String quality : qualities) {
            if (StringUtils.startsWithCaseInsensitive(qualityID, quality)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Given width may not always be exactly what we have in our quality selection but we need an exact value to make the user selection
     * work properly!
     */
    private String getHeightForQualitySelection(final int height) {
        final String heightselect;
        if (height > 0 && height <= 200) {
            heightselect = "170";
        } else if (height > 200 && height <= 300) {
            heightselect = "270";
        } else if (height > 300 && height <= 400) {
            heightselect = "360";
        } else if (height > 400 && height <= 500) {
            heightselect = "480";
        } else if (height > 500 && height <= 600) {
            heightselect = "570";
        } else if (height > 600 && height <= 800) {
            heightselect = "720";
        } else {
            /* Either unknown quality or audio (0x0) */
            heightselect = Integer.toString(height);
        }
        return heightselect;
    }

    private HashMap<String, DownloadLink> findBESTInsideGivenMap(final HashMap<String, DownloadLink> bestMap) {
        HashMap<String, DownloadLink> newMap = new HashMap<String, DownloadLink>();
        DownloadLink keep = null;
        if (bestMap.size() > 0) {
            for (final String quality : all_known_qualities) {
                keep = bestMap.get(quality);
                if (keep != null) {
                    newMap.put(quality, keep);
                    break;
                }
            }
        }
        if (newMap.isEmpty()) {
            /* Failover in case of bad user selection or general failure! */
            newMap = bestMap;
        }
        return newMap;
    }

    private void addDownloadLink(final DownloadLink dl) {
        decryptedLinks.add(dl);
        if (grabSubtitles && !StringUtils.isEmpty(this.url_subtitle)) {
            final String current_ext = dl.getFinalFileName().substring(dl.getFinalFileName().lastIndexOf("."));
            final String final_filename = dl.getFinalFileName().replace(current_ext, ".xml");
            final String linkid = dl.getLinkID() + "_subtitle";
            final DownloadLink dl_subtitle = this.createDownloadlink(this.url_subtitle);
            setDownloadlinkProperties(dl_subtitle, final_filename, "subtitle", linkid, null, null, null, null);
            if (filesizeSubtitle > 0) {
                dl_subtitle.setDownloadSize(filesizeSubtitle);
                dl_subtitle.setAvailable(true);
            }
            decryptedLinks.add(dl_subtitle);
        }
    }

    private void setDownloadlinkProperties(final DownloadLink dl, final String final_filename, final String streamingType, final String linkid, final String title, final String tv_show, final String date_formatted, final String tv_station) {
        dl.setFinalFileName(final_filename);
        dl.setLinkID(linkid);
        /* Very important! */
        dl.setProperty("streamingType", streamingType);
        /* The following properties are only relevant for packagizer usage. */
        if (!StringUtils.isEmpty(title)) {
            dl.setProperty("title", title);
        }
        if (!StringUtils.isEmpty(tv_show)) {
            dl.setProperty("tv_show", tv_show);
        }
        if (!StringUtils.isEmpty(date_formatted)) {
            dl.setProperty("date_formatted", date_formatted);
        }
        if (!StringUtils.isEmpty(tv_station)) {
            dl.setProperty("tv_station", tv_station);
        }
        dl.setContentUrl(PARAMETER_ORIGINAL);
    }

    private void accessPlayerJson(final String api_base, final String player_url_template, final String playerID) throws IOException {
        /* E.g. "/tmd/2/{playerId}/vod/ptmd/mediathek/161215_sendungroyale065ddm_nmg" */
        String player_url = player_url_template.replace("{playerId}", playerID);
        if (player_url.startsWith("/")) {
            player_url = api_base + player_url;
        }
        this.br.getPage(player_url);
    }

    /**
     * Validates string to series of conditions, null, whitespace, or "". This saves effort factor within if/for/while statements
     *
     * @param s
     *            Imported String to match against.
     * @return <b>true</b> on valid rule match. <b>false</b> on invalid rule match.
     * @author raztoki
     */
    protected boolean inValidate(final String s) {
        if (s == null || s.matches("\\s+") || s.equals("")) {
            return true;
        } else {
            return false;
        }
    }

    public static String formatDateZDF(String input) {
        final long date;
        if (input.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+\\d{2}:\\d{2}")) {
            /* tivi.de */
            input = input.substring(0, input.lastIndexOf(":")) + "00";
            date = TimeFormatter.getMilliSeconds(input, "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.GERMAN);
        } else {
            /* zdf.de/zdfmediathek */
            date = TimeFormatter.getMilliSeconds(input, "dd.MM.yyyy HH:mm", Locale.GERMAN);
        }
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

    private String convertInternalAudioClassToUserReadable(final String audio_class) {
        if (audio_class == null) {
            return null;
        } else if (audio_class.equals("main")) {
            return "TV_Ton";
        } else if (audio_class.equals("ad")) {
            return "Audiodeskription";
        } else {
            /* This should never happen! */
            return "Ton_unbekannt";
        }
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}