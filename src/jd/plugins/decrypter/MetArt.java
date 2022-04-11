package jd.plugins.decrypter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.components.config.MetartConfig;
import org.jdownloader.plugins.components.config.MetartConfig.PhotoCrawlMode;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.AccountRequiredException;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.hoster.MetArtCom;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class MetArt extends PluginForDecrypt {
    public MetArt(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.IMAGE_GALLERY, LazyPlugin.FEATURE.XXX };
    }

    /** Sync this list for hoster + crawler plugin! */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "metart.com" });
        ret.add(new String[] { "sexart.com" });
        ret.add(new String[] { "alsscan.com" });
        ret.add(new String[] { "domai.com" });
        ret.add(new String[] { "eroticbeauty.com" });
        ret.add(new String[] { "errotica-archives.com" });
        ret.add(new String[] { "eternaldesire.com" });
        ret.add(new String[] { "goddessnudes.com" });
        ret.add(new String[] { "lovehairy.com" });
        ret.add(new String[] { "metartx.com" });
        ret.add(new String[] { "rylskyart.com" });
        ret.add(new String[] { "stunning18.com" });
        ret.add(new String[] { "thelifeerotic.com" });
        ret.add(new String[] { "vivthomas.com" });
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
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/model/[^/]+/(gallery|movie)/\\d+/[A-Za-z0-9\\-_]+");
        }
        return ret.toArray(new String[0]);
    }

    private static final String TYPE_GALLERY = "https://[^/]+/model/([^/]+)/gallery/(\\d+)/([^/]+)";
    private static final String TYPE_MOVIE   = "https://[^/]+/model/([^/]+)/movie/(\\d+)/([^/]+)";

    @Override
    public int getMaxConcurrentProcessingInstances() {
        /* 2020-12-07: Preventive measure */
        return 1;
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, final ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final ArrayList<Account> accounts = AccountController.getInstance().getAllAccounts(this.getHost());
        Account useAcc = null;
        if (accounts != null && accounts.size() != 0) {
            Iterator<Account> it = accounts.iterator();
            while (it.hasNext()) {
                Account n = it.next();
                if (n.isEnabled() && n.isValid()) {
                    useAcc = n;
                    break;
                }
            }
        }
        if (useAcc == null) {
            throw new AccountRequiredException();
        }
        final PluginForHost plg = this.getNewPluginForHostInstance(this.getHost());
        plg.setBrowser(this.br);
        ((jd.plugins.hoster.MetArtCom) plg).login(useAcc, false);
        br.setFollowRedirects(true);
        if (param.getCryptedUrl().matches(TYPE_GALLERY)) {
            /* New 2020-12-07 */
            final Regex urlinfo = new Regex(param.getCryptedUrl(), TYPE_GALLERY);
            final String modelname = urlinfo.getMatch(0);
            final String date = urlinfo.getMatch(1);
            final String galleryname = urlinfo.getMatch(2);
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(modelname + " - " + date + " - " + galleryname);
            if (PluginJsonConfig.get(getLazyC(), MetartConfig.class).getPhotoCrawlMode() == PhotoCrawlMode.ZIP_BEST) {
                br.getPage("https://www." + this.getHost() + "/api/gallery?name=" + galleryname + "&date=" + date + "&mediaFirst=42&page=1");
                if (br.getHttpConnection().getResponseCode() == 404) {
                    ret.add(this.createOfflinelink(param.getCryptedUrl()));
                    return ret;
                }
                Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
                final String uuid = (String) entries.get("UUID");
                entries = (Map<String, Object>) entries.get("files");
                entries = (Map<String, Object>) entries.get("sizes");
                final List<Object> zipFilesO = (List<Object>) entries.get("zips");
                /* Assume that their array is sorted from highest to lowest. */
                entries = (Map<String, Object>) zipFilesO.get(0);
                String filename = (String) entries.get("fileName");
                if (!filename.toLowerCase(Locale.ENGLISH).endsWith(".zip")) {
                    filename += ".zip";
                }
                final String filesize = (String) entries.get("size");
                final String quality = (String) entries.get("quality");
                final DownloadLink dl = this.createDownloadlink("https://www.metart.com/api/download-media/" + uuid + "/photos/" + quality);
                dl.setFinalFileName(filename);
                dl.setDownloadSize(SizeFormatter.getSize(filesize));
                dl.setAvailable(true);
                dl._setFilePackage(fp);
                dl.setProperty(MetArtCom.PROPERTY_UUID, uuid);
                dl.setProperty(MetArtCom.PROPERTY_QUALITY, quality);
                ret.add(dl);
            } else {
                br.getPage("https://www." + this.getHost() + "/api/image?name=" + galleryname + "&date=" + date + "&order=5&mediaType=gallery");
                if (br.getHttpConnection().getResponseCode() == 404) {
                    ret.add(this.createOfflinelink(param.getCryptedUrl()));
                    return ret;
                }
                Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
                final List<Object> imagesO = (List<Object>) entries.get("media");
                for (final Object imageO : imagesO) {
                    entries = (Map<String, Object>) imageO;
                    final String uuid = (String) entries.get("UUID");
                    final String url = (String) JavaScriptEngineFactory.walkJson(entries, "src_downloadable/high");
                    if (StringUtils.isEmpty(url) || StringUtils.isEmpty(uuid)) {
                        /* Skip invalid objects */
                        continue;
                    }
                    final String filenameURL = UrlQuery.parse(url).get("filename");
                    final DownloadLink dl = new DownloadLink(plg, filenameURL, this.getHost(), url, true);
                    dl.setAvailable(true);
                    if (filenameURL != null) {
                        dl.setName(filenameURL);
                    }
                    dl._setFilePackage(fp);
                    dl.setProperty(MetArtCom.PROPERTY_UUID, uuid);
                    dl.setProperty(MetArtCom.PROPERTY_QUALITY, "high");
                    ret.add(dl);
                }
            }
        } else if (param.getCryptedUrl().matches(TYPE_MOVIE)) {
            /* New 2020-12-08 */
            final Regex urlinfo = new Regex(param.getCryptedUrl(), TYPE_MOVIE);
            final String modelname = urlinfo.getMatch(0);
            final String date = urlinfo.getMatch(1);
            final String galleryname = urlinfo.getMatch(2);
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(modelname + " - " + date + " - " + galleryname);
            br.getPage("https://www." + this.getHost() + "/api/movie?name=" + galleryname + "&date=" + date);
            if (br.getHttpConnection().getResponseCode() == 404) {
                ret.add(this.createOfflinelink(param.getCryptedUrl()));
                return ret;
            }
            Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            final String title = (String) entries.get("name");
            final String description = (String) entries.get("description");
            final String uuid = (String) entries.get("UUID");
            if (StringUtils.isEmpty(title) || StringUtils.isEmpty(uuid)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            entries = (Map<String, Object>) entries.get("files");
            final List<Object> teasersO = (List<Object>) entries.get("teasers");
            Map<String, Object> sizes = (Map<String, Object>) entries.get("sizes");
            final List<Object> videoQualitiesO = (List<Object>) sizes.get("videos");
            for (final Object videoO : videoQualitiesO) {
                entries = (Map<String, Object>) videoO;
                final String id = (String) entries.get("id");
                final String filesizeStr = (String) entries.get("size");
                if (StringUtils.isEmpty(id) || StringUtils.isEmpty(filesizeStr)) {
                    /* Skip invalid objects */
                    continue;
                }
                final String ext;
                if (id.matches("\\d+p")) {
                    ext = "mp4";
                } else if (id.equalsIgnoreCase("4k")) {
                    ext = "mp4";
                } else {
                    /* E.g. avi, wmv */
                    ext = id;
                }
                final String downloadurl = "https://www.metart.com/api/download-media/" + uuid + "/film/" + id;
                String filename = modelname + " - " + title;
                /* Do not e.g. generate filenames like "title_avi.avi" */
                if (!ext.equals(id)) {
                    filename += "_" + id;
                }
                filename += "." + ext;
                final DownloadLink dl = new DownloadLink(plg, filename, this.getHost(), downloadurl, true);
                dl.setDownloadSize(SizeFormatter.getSize(filesizeStr));
                dl.setAvailable(true);
                /* Prefer server-filename which will be set on downloadstart. */
                // dl.setFinalFileName(modelname + " - " + title + "_" + id + "." + ext);
                if (!StringUtils.isEmpty(description)) {
                    dl.setComment(description);
                }
                dl._setFilePackage(fp);
                dl.setProperty(MetArtCom.PROPERTY_UUID, uuid);
                dl.setProperty(MetArtCom.PROPERTY_QUALITY, id);
                ret.add(dl);
            }
            if (ret.isEmpty() && !teasersO.isEmpty()) {
                /* No downloads found -> Fallback to trailer download */
                final DownloadLink trailer = this.createDownloadlink(br.getURL("/api/m3u8/" + uuid + "/720.m3u8").toString().replaceFirst("https?://", "m3u8s://"));
                trailer.setAvailable(true);
                trailer.setFinalFileName(modelname + " - " + title + " - teaser.mp4");
                trailer._setFilePackage(fp);
                ret.add(trailer);
            } else if (ret.isEmpty()) {
                /* Rare case */
                logger.info("Failed to find any downloadable content");
                ret.add(this.createOfflinelink(param.getCryptedUrl()));
                return ret;
            }
        } else {
            /* Unsupported URL */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        return ret;
    }

    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}