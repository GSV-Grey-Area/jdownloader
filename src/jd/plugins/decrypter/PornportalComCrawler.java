//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.components.SiteType.SiteTemplate;
import jd.plugins.hoster.PornportalCom;
import jd.utils.JDUtilities;

import org.appwork.utils.StringUtils;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.components.config.PornportalComConfig;
import org.jdownloader.plugins.components.config.PornportalComConfig.FilenameScheme;
import org.jdownloader.plugins.components.config.PornportalComConfig.QualitySelectionMode;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class PornportalComCrawler extends PluginForDecrypt {
    public PornportalComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "babes.com" });
        ret.add(new String[] { "bellesafilms.com" });
        ret.add(new String[] { "biempire.com" });
        /*
         * 2020-04-30: Special: They have an old- and new sytem running in parallel. Old = https://ma.brazzers.com/access/login/ new
         * (pornportal) = https://site-ma.brazzers.com/login
         */
        ret.add(new String[] { "brazzers.com" });
        ret.add(new String[] { "digitalplayground.com" });
        ret.add(new String[] { "erito.com", "eritos.com" });
        ret.add(new String[] { "fakehub.com" });
        ret.add(new String[] { "hentaipros.com" });
        ret.add(new String[] { "lilhumpers.com" });
        ret.add(new String[] { "milehighmedia.com", "sweetheartvideo.com", "realityjunkies.com" });
        ret.add(new String[] { "metrohd.com", "familyhookups.com", "kinkyspa.com" });
        ret.add(new String[] { "mofos.com", "publicpickups.com", "iknowthatgirl.com", "dontbreakme.com" });
        ret.add(new String[] { "propertysex.com" });
        ret.add(new String[] { "realitykings.com", "gfleaks.com", "inthevip.com", "mikesapartment.com", "8thstreetlatinas.com", "bignaturals.com", "cumfiesta.com", "happytugs.com", "milfhunter.com", "momsbangteens.com", "momslickteens.com", "moneytalks.com", "roundandbrown.com", "sneakysex.com", "teenslovehugecocks.com", "welivetogether.com" });
        ret.add(new String[] { "sexyhub.com" });
        ret.add(new String[] { "spankwirepremium.com" });
        ret.add(new String[] { "squirted.com" });
        ret.add(new String[] { "transangels.com" });
        ret.add(new String[] { "transsensual.com" });
        ret.add(new String[] { "trueamateurs.com" });
        ret.add(new String[] { "twistys.com" });
        ret.add(new String[] { "whynotbi.com" });
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
            // final String annotationName = domains[0];
            /* Premium URLs */
            String pattern = "https?://site-ma\\." + buildHostsPatternPart(domains) + "/(?:trailer|scene|series)/(\\d+)(/[a-z0-9\\-]+)?";
            /* Free URLs */
            pattern += "|https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:scene|series)/(\\d+)(/[a-z0-9\\-]+)?";
            // if (annotationName.equals("digitalplayground.com")) {
            // pattern += "|https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/scene/(\\d+)(/[a-z0-9\\-]+)?";
            // }
            ret.add(pattern);
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        /* Login if possible */
        final Account acc = getUserLogin();
        if (acc == null) {
            /* Anonymous API auth */
            logger.info("No account given --> Trailer download");
            if (!PornportalCom.prepareBrAPI(this, br, null)) {
                logger.info("Getting fresh API data");
                br.getPage("https://site-ma." + Browser.getHost(param.getCryptedUrl(), false) + "/login");
                if (!PornportalCom.prepareBrAPI(this, br, null)) {
                    logger.warning("Failed to set required API headers");
                    return null;
                }
            }
        }
        final String contentID = new Regex(param.getCryptedUrl(), "(?:trailer|scene|series)/(\\d+)").getMatch(0);
        if (contentID == null) {
            return null;
        }
        final PluginForHost hostPlugin = JDUtilities.getPluginForHost(this.getHost());
        /* Crawll all items */
        final HashMap<String, DownloadLink> qualities = crawlContentAPI(hostPlugin, this.br, contentID, acc);
        if (qualities.isEmpty()) {
            logger.warning("Found nothing");
            return null;
        }
        final PornportalComConfig cfg = PluginJsonConfig.get(PornportalComConfig.class);
        final List<String> selectedQualities = new ArrayList<String>();
        if (cfg.isSelectQuality2160()) {
            selectedQualities.add("2160");
        }
        if (cfg.isSelectQuality1080()) {
            selectedQualities.add("1080");
        }
        if (cfg.isSelectQuality720()) {
            selectedQualities.add("720");
        }
        if (cfg.isSelectQuality480()) {
            selectedQualities.add("480");
        }
        if (cfg.isSelectQuality360()) {
            selectedQualities.add("360");
        }
        /* Add user selected quality */
        final ArrayList<DownloadLink> foundSelection = new ArrayList<DownloadLink>();
        if (cfg.getQualitySelectionMode() == QualitySelectionMode.ALL_SELECTED) {
            for (final String selectedQuality : selectedQualities) {
                if (qualities.containsKey(selectedQuality)) {
                    foundSelection.add(qualities.get(selectedQuality));
                }
            }
        } else {
            /* BEST quality only */
            /* Known qualities sorted best -> Worst */
            final String[] allKnownQualities = new String[] { "2160", "1080", "720", "480", "360" };
            for (final String knownQuality : allKnownQualities) {
                if (qualities.containsKey(knownQuality)) {
                    /* We found the best quality */
                    foundSelection.add(qualities.get(knownQuality));
                    break;
                }
            }
        }
        if (!foundSelection.isEmpty()) {
            return foundSelection;
        } else {
            /* Fallback: Add all qualities if none were found by selection */
            final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
            final Iterator<Entry<String, DownloadLink>> iteratorQualities = qualities.entrySet().iterator();
            while (iteratorQualities.hasNext()) {
                ret.add(iteratorQualities.next().getValue());
            }
            return ret;
        }
    }

    public boolean isProxyRotationEnabledForLinkCrawler() {
        return false;
    }

    private Account getUserLogin() throws Exception {
        final PluginForHost hostPlugin = JDUtilities.getPluginForHost(this.getHost());
        Account aa = AccountController.getInstance().getValidAccount(this.getHost());
        if (aa == null) {
            /*
             * Try 'internal multihoster' handling e.g. user may have added account for erito.com which also grants premium access to other
             * sites e.g. fakehub.com.
             */
            logger.info("Failed to find main account --> Looking for 'multihoster account'");
            final ArrayList<String> allowedPornportalHosts = PornportalCom.getAllSupportedPluginDomainsFlat();
            final List<Account> multihostAccounts = AccountController.getInstance().getMultiHostAccounts(this.getHost());
            for (final Account multihostAcc : multihostAccounts) {
                final String multiHostHost = multihostAcc.getHoster();
                if (multihostAcc.isEnabled() && allowedPornportalHosts.contains(multiHostHost)) {
                    logger.info("Found working multihost account: " + multihostAcc.getHoster());
                    aa = multihostAcc;
                    break;
                }
            }
        }
        if (aa != null) {
            try {
                ((jd.plugins.hoster.PornportalCom) hostPlugin).login(this.br, aa, this.getHost(), false);
                return aa;
            } catch (final PluginException e) {
                // handleAccountException(aa, e);
                logger.info("Login failure --> Continue without account / trailer download");
                return null;
            }
        }
        return aa;
    }

    public static HashMap<String, DownloadLink> crawlContentAPI(final PluginForHost plg, final Browser br, final String contentID, final Account account) throws Exception {
        final PornportalComConfig cfg = PluginJsonConfig.get(PornportalComConfig.class);
        final FilenameScheme filenameScheme = cfg.getFilenameScheme();
        final String host = plg.getHost();
        final HashMap<String, DownloadLink> foundQualities = new HashMap<String, DownloadLink>();
        String api_base = PluginJSonUtils.getJson(br, "dataApiUrl");
        if (StringUtils.isEmpty(api_base)) {
            /* Fallback to static value e.g. loggedIN --> html containing json API information has not been accessed before */
            api_base = "https://site-api.project1service.com";
        }
        br.getPage(api_base + "/v2/releases/" + contentID);
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        Map<String, Object> root = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
        final Map<String, Object> result = (Map<String, Object>) root.get("result");
        final ArrayList<Map<String, Object>> videoObjects = new ArrayList<Map<String, Object>>();
        /* Add current object - that itself could be a video object! */
        videoObjects.add(result);
        /* Look for more objects e.g. video split into multiple parts/scenes(??!) */
        final Object videoChildrenO = result.get("children");
        if (videoChildrenO != null) {
            final List<Map<String, Object>> children = (List<Map<String, Object>>) videoChildrenO;
            videoObjects.addAll(children);
        }
        final boolean isPremium = account != null && account.getType() == AccountType.PREMIUM;
        for (final Object videoO : videoObjects) {
            final Map<String, Object> clipInfo = (Map<String, Object>) videoO;
            final String type = (String) clipInfo.get("type");
            // final String type = (String) entries.get("type");
            final String videoID = Long.toString(JavaScriptEngineFactory.toLong(clipInfo.get("id"), 0));
            if (StringUtils.isEmpty(type) || !type.matches("trailer|full|scene")) {
                /* Skip unsupported video types */
                continue;
            } else if (StringUtils.isEmpty(videoID)) {
                /* Skip invalid objects */
                continue;
            }
            String title = (String) clipInfo.get("title");
            String description = (String) clipInfo.get("description");
            if (StringUtils.isEmpty(title)) {
                /* Fallback */
                title = contentID;
            } else if (title.equalsIgnoreCase("trailer")) {
                title = contentID + "_trailer";
            }
            if (isPremium && type.equals("trailer")) {
                plg.getLogger().info("Skipping trailer because user owns premium account");
                continue;
            }
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(title);
            if (!StringUtils.isEmpty(description)) {
                fp.setComment(description);
            }
            final List<Map<String, Object>> allFullVideos = new ArrayList<Map<String, Object>>();
            final List<Map<String, Object>> allTrailers = new ArrayList<Map<String, Object>>();
            try {
                final Map<String, Object> videoTypesMap = (Map<String, Object>) clipInfo.get("videos");
                final Map<String, Object> fullVideoRenditions = (Map<String, Object>) JavaScriptEngineFactory.walkJson(videoTypesMap, "full/files");
                final Map<String, Object> trailerRenditions = (Map<String, Object>) JavaScriptEngineFactory.walkJson(videoTypesMap, "mediabook/files");
                if (fullVideoRenditions == null && trailerRenditions == null) {
                    /* Skip non-video objects */
                    continue;
                } else {
                    if (fullVideoRenditions != null) {
                        allFullVideos.add(fullVideoRenditions);
                    }
                    if (trailerRenditions != null) {
                        allTrailers.add(trailerRenditions);
                    }
                }
            } catch (final Throwable e) {
                /* Skip non-video objects */
                continue;
            }
            /* Now walk through all qualities in all types */
            final List<Map<String, Object>> videoRenditionsToProcess;
            /* Prefer full videos over trailers */
            if (!allFullVideos.isEmpty()) {
                videoRenditionsToProcess = allFullVideos;
            } else {
                videoRenditionsToProcess = allTrailers;
            }
            for (final Map<String, Object> files : videoRenditionsToProcess) {
                final Iterator<Entry<String, Object>> qualities = files.entrySet().iterator();
                while (qualities.hasNext()) {
                    final Entry<String, Object> entry = qualities.next();
                    final Map<String, Object> videoInfo = (Map<String, Object>) entry.getValue();
                    final Object urlsO = videoInfo.get("urls");
                    if (urlsO instanceof List) {
                        /* Usually empty list --> Usually means that current clip is not available as full clip -> Trailer only */
                        continue;
                    }
                    String qualityIdentifier = (String) videoInfo.get("format");
                    final long filesize = JavaScriptEngineFactory.toLong(videoInfo.get("sizeBytes"), 0);
                    final Map<String, Object> downloadInfo = (Map<String, Object>) urlsO;
                    String downloadurl = (String) downloadInfo.get("download");
                    if (StringUtils.isEmpty(downloadurl)) {
                        /* Fallback to stream-URL (most times, an official downloadurl is available!) */
                        downloadurl = (String) downloadInfo.get("view");
                    }
                    if (StringUtils.isEmpty(downloadurl)) {
                        continue;
                    } else if (StringUtils.isEmpty(qualityIdentifier) || !qualityIdentifier.matches("\\d+p")) {
                        /* Skip invalid entries and hls and dash streams */
                        continue;
                    }
                    /* E.g. '1080p' --> '1080' */
                    qualityIdentifier = qualityIdentifier.replace("p", "");
                    String contentURL;
                    final String patternMatcher;
                    final DownloadLink dl;
                    if (account != null) {
                        /* Download with account */
                        patternMatcher = "https://decrypted" + host + "/" + videoID + "/" + qualityIdentifier;
                        contentURL = "https://site-ma." + host + "/scene/" + videoID;
                        dl = new DownloadLink(plg, "pornportal", host, patternMatcher, true);
                    } else {
                        /* Without account users can only download trailers and their directurls never expire. */
                        patternMatcher = downloadurl;
                        contentURL = patternMatcher;
                        dl = new DownloadLink(JDUtilities.getPluginForHost("DirectHTTP"), "pornportal", host, patternMatcher, true);
                    }
                    dl.setContentUrl(contentURL);
                    final String originalFilename = UrlQuery.parse(downloadurl).get("filename");
                    if (filenameScheme == FilenameScheme.ORIGINAL && originalFilename != null) {
                        dl.setFinalFileName(originalFilename);
                    } else if (filenameScheme == FilenameScheme.VIDEO_ID_TITLE_QUALITY_EXT) {
                        dl.setFinalFileName(videoID + "_" + title + "_" + qualityIdentifier + ".mp4");
                    } else {
                        dl.setFinalFileName(title + "_" + qualityIdentifier + ".mp4");
                    }
                    dl.setProperty("videoid", videoID);
                    dl.setProperty("quality", qualityIdentifier);
                    dl.setProperty("directurl", downloadurl);
                    if (filesize > 0) {
                        dl.setDownloadSize(filesize);
                    }
                    dl.setAvailable(true);
                    dl._setFilePackage(fp);
                    foundQualities.put(qualityIdentifier, dl);
                }
                if (!foundQualities.isEmpty()) {
                    break;
                } else {
                    /* This should never happen! */
                    plg.getLogger().warning("Failed to find any downloadable content for videoID: " + videoID);
                    break;
                }
            }
        }
        return foundQualities;
    }

    @Override
    public Class<? extends PornportalComConfig> getConfigInterface() {
        return PornportalComConfig.class;
    }

    public static String getProtocol() {
        return "https://";
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.PornPortal;
    }
}