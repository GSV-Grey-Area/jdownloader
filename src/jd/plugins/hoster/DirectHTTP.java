//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
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
package jd.plugins.hoster;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
import jd.config.SubConfiguration;
import jd.controlling.linkchecker.LinkChecker;
import jd.controlling.linkcrawler.CheckableLink;
import jd.controlling.linkcrawler.CrawledLink;
import jd.controlling.linkcrawler.LinkCrawler;
import jd.controlling.linkcrawler.LinkCrawlerRule;
import jd.controlling.reconnect.ipcheck.IP;
import jd.http.Authentication;
import jd.http.AuthenticationFactory;
import jd.http.Browser;
import jd.http.CallbackAuthenticationFactory;
import jd.http.Cookies;
import jd.http.DefaultAuthenticanFactory;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.http.URLUserInfoAuthentication;
import jd.nutils.SimpleFTP;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.FilePackage;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.download.Downloadable;
import jd.utils.locale.JDL;

import org.appwork.net.protocol.http.HTTPConstants;
import org.appwork.storage.TypeRef;
import org.appwork.utils.Files;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.net.URLHelper;
import org.appwork.utils.net.httpconnection.HTTPConnection.RequestMethod;
import org.appwork.utils.net.httpconnection.HTTPConnectionUtils.DispositionHeader;
import org.jdownloader.auth.AuthenticationController;
import org.jdownloader.auth.AuthenticationInfo;
import org.jdownloader.auth.AuthenticationInfo.Type;
import org.jdownloader.auth.Login;
import org.jdownloader.plugins.SkipReasonException;
import org.jdownloader.plugins.components.antiDDoSForHost;
import org.jdownloader.plugins.controller.LazyPlugin;

/**
 * TODO: remove after next big update of core to use the public static methods!
 */
@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "DirectHTTP", "http links" }, urls = { "directhttp://.+",
"https?(viajd)?://[^/]+/.*\\.((jdeatme|3gp|7zip|7z|abr|ac3|ace|aiff|aifc|aif|ai|au|avi|avif|appimage|apk|azw3|azw|adf|asc|bin|ape|ass|bmp|bat|bz2|cbr|csv|cab|cbz|ccf|chm|cr2|cso|cue|cpio|cvd|c\\d{2,4}|chd|dta|deb|divx|djvu|dlc|dmg|doc|docx|dot|dx2|eps|epub|exe|ff|flv|flac|f4v|gsd|gif|gpg|gz|hqx|iwd|idx|iso|ipa|ipsw|java|jar|jpe?g|jp2|load|lha|lzh|m2ts|m4v|m4a|md5|midi?|mkv|mp2|mp3|mp4|mobi|mov|movie|mpeg|mpe|mpg|mpq|msi|msu|msp|mv|mws|nfo|npk|nsf|oga|ogg|ogm|ogv|otrkey|par2|pak|pkg|png|pdf|pptx?|ppsx?|ppz|pdb|pot|psd|ps|qt|rmvb|rm|rar|ra|rev|rnd|rpm|run|rsdf|reg|rtf|shnf|sh(?!tml)|ssa|smi|sig|sub|srt|snd|sfv|sfx|swf|swc|sid|sit|tar\\.(gz|bz2|xz)|tar|tgz|tiff?|ts|txt|viv|vivo|vob|vtt|webm|webp|wav|wad|wmv|wma|wpt|xla|xls|xpi|xtm|zeno|zip|[r-z]\\d{2}|_?[_a-z]{2}|\\d{1,4}$)(\\.\\d{1,4})?(?=\\?|$|#|\"|\r|\n|;))" })
public class DirectHTTP extends antiDDoSForHost {
    public static final String  ENDINGS                  = "\\.(jdeatme|3gp|7zip|7z|abr|ac3|ace|aiff|aifc|aif|ai|au|avi|avif|appimage|apk|azw3|azw|adf|asc|ape|bin|ass|bmp|bat|bz2|cbr|csv|cab|cbz|ccf|chm|cr2|cso|cue|cpio|cvd|c\\d{2,4}|chd|dta|deb|divx|djvu|dlc|dmg|doc|docx|dot|dx2|eps|epub|exe|ff|flv|flac|f4v|gsd|gif|gpg|gz|hqx|iwd|idx|iso|ipa|ipsw|java|jar|jpe?g|jp2|load|lha|lzh|m2ts|m4v|m4a|md5|midi?|mkv|mp2|mp3|mp4|mobi|mov|movie|mpeg|mpe|mpg|mpq|msi|msu|msp|mv|mws|nfo|npk|nfs|oga|ogg|ogm|ogv|otrkey|par2|pak|pkg|png|pdf|pptx?|ppsx?|ppz|pdb|pot|psd|ps|qt|rmvb|rm|rar|ra|rev|rnd|rpm|run|rsdf|reg|rtf|shnf|sh(?!tml)|ssa|smi|sig|sub|srt|snd|sfv|sfx|swf|swc|sid|sit|tar\\.(gz|bz2|xz)|tar|tgz|tiff?|ts|txt|viv|vivo|vob|vtt|webm|webp|wav|wad|wmv|wma|wpt|xla|xls|xpi|xtm|zeno|zip|[r-z]\\d{2}|_?[_a-z]{2}|\\d{1,4}(?=\\?|$|#|\"|\r|\n|;))";
    public static final String  NORESUME                 = "nochunkload";
    public static final String  NOCHUNKS                 = "nochunk";
    /**
     * Set this property on DownloadLink objects if you want to force a filename which also survives if the user resets a DownloadLink.
     * Otherwise, Content-Disposition filename will be used (or filename from inside URL as fallback).
     */
    public static final String  FIXNAME                  = "fixName";
    public static final String  FORCE_NORESUME           = "forcenochunkload";
    public static final String  FORCE_NOCHUNKS           = "forcenochunk";
    public static final String  FORCE_NOVERIFIEDFILESIZE = "forcenoverifiedfilesize";
    public static final String  TRY_ALL                  = "tryall";
    public static final String  POSSIBLE_URLPARAM        = "POSSIBLE_GETPARAM";
    public static final String  BYPASS_CLOUDFLARE_BGJ    = "bpCfBgj";
    public static final String  PROPERTY_COOKIES         = "COOKIES";
    public static final String  PROPERTY_MAX_CONCURRENT  = "PROPERTY_MAX_CONCURRENT";
    public static final String  PROPERTY_RATE_LIMIT      = "PROPERTY_RATE_LIMIT";
    public static final String  PROPERTY_RATE_LIMIT_TLD  = "PROPERTY_RATE_LIMIT_TLD";
    public static final String  PROPERTY_REQUEST_TYPE    = "requestType";
    private static final String PROPERTY_DISABLE_PREFIX  = "disable_";
    private static final String PROPERTY_ENABLE_PREFIX   = "enable_";
    private static final String PROPERTY_OPTION_SET      = "optionSet";

    @Override
    public ArrayList<DownloadLink> getDownloadLinks(final String data, final FilePackage fp) {
        final ArrayList<DownloadLink> ret = super.getDownloadLinks(data, fp);
        if (ret != null && ret.size() == 1) {
            preProcessDirectHTTP(ret.get(0), data);
        }
        return ret;
    }

    private void preProcessDirectHTTP(final DownloadLink downloadLink, final String data) {
        try {
            String modifiedData = null;
            final boolean isDirect;
            final boolean tryAll = StringUtils.containsIgnoreCase(data, ".jdeatme");
            if (data.startsWith("directhttp://")) {
                isDirect = true;
                modifiedData = data.replace("directhttp://", "");
            } else {
                isDirect = false;
                modifiedData = data.replace("httpsviajd://", "https://");
                modifiedData = modifiedData.replace("httpviajd://", "http://");
                modifiedData = modifiedData.replace(".jdeatme", "");
            }
            final DownloadLink link = downloadLink;
            if (tryAll) {
                link.setProperty(TRY_ALL, Boolean.TRUE);
            }
            correctDownloadLink(link);// needed to fixup the returned url
            CrawledLink currentLink = getCurrentLink();
            while (currentLink != null) {
                if (!StringUtils.equals(currentLink.getURL(), data)) {
                    link.setProperty(LinkCrawler.PROPERTY_AUTO_REFERER, currentLink.getURL());
                    break;
                } else {
                    currentLink = currentLink.getSourceLink();
                }
            }
            /* single link parsing in svn/jd2 */
            final String url = link.getPluginPatternMatcher();
            final int idx = modifiedData.indexOf(url);
            if (!isDirect && idx >= 0 && modifiedData.length() >= idx + url.length()) {
                String param = modifiedData.substring(idx + url.length());
                if (param != null) {
                    param = new Regex(param, "(.*?)(\r|\n|$)").getMatch(0);
                    if (param != null && param.trim().length() != 0) {
                        link.setProperty(DirectHTTP.POSSIBLE_URLPARAM, new String(param));
                    }
                }
            }
        } catch (final Throwable e) {
            if (logger != null) {
                this.logger.log(e);
            }
        }
    }

    @Override
    public String getMirrorID(final DownloadLink link) {
        final String mirrorID = link != null ? link.getStringProperty("mirrorID", null) : null;
        return mirrorID;
    }

    @Override
    public boolean isValidURL(String url) {
        if (url != null) {
            if (StringUtils.startsWithCaseInsensitive(url, "directhttp")) {
                return true;
            } else {
                url = url.toLowerCase(Locale.ENGLISH);
                if (url.contains("facebook.com/l.php")) {
                    return false;
                } else if (url.contains("facebook.com/ajax/sharer/")) {
                    return false;
                } else if (url.contains("youtube.com/videoplayback") && url.startsWith("http")) {
                    return false;
                } else if (url.matches(".*?://.*?/.*\\?.*\\.\\d+$")) {
                    return false;
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isResumeable(DownloadLink link, Account account) {
        if (link != null) {
            if (link.getBooleanProperty(DirectHTTP.NORESUME, false) || link.getBooleanProperty(DirectHTTP.FORCE_NORESUME, false)) {
                return false;
            } else if (link.getBooleanProperty(DownloadLink.PROPERTY_RESUMEABLE, true)) {
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public boolean isSpeedLimited(DownloadLink link, Account account) {
        return false;
    }

    /**
     * TODO: Remove with next major-update!
     */
    public static ArrayList<String> findUrls(final String source) {
        /* TODO: better parsing */
        /* remove tags!! */
        final ArrayList<String> ret = new ArrayList<String>();
        try {
            for (final String link : new Regex(source, "((https?|ftp):((//)|(\\\\\\\\))+[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]*)(\n|\r|$|<|\")").getColumn(0)) {
                try {
                    new URL(link);
                    if (!ret.contains(link)) {
                        ret.add(link);
                    }
                } catch (final MalformedURLException e) {
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
        return DirectHTTP.removeDuplicates(ret);
    }

    /**
     * TODO: Remove with next major-update!
     */
    public static ArrayList<String> removeDuplicates(final ArrayList<String> links) {
        final ArrayList<String> tmplinks = new ArrayList<String>();
        if (links == null || links.size() == 0) {
            return tmplinks;
        }
        for (final String link : links) {
            if (link.contains("...")) {
                final String check = link.substring(0, link.indexOf("..."));
                String found = link;
                for (final String link2 : links) {
                    if (link2.startsWith(check) && !link2.contains("...")) {
                        found = link2;
                        break;
                    }
                }
                if (!tmplinks.contains(found)) {
                    tmplinks.add(found);
                }
            } else {
                tmplinks.add(link);
            }
        }
        return tmplinks;
    }

    public DirectHTTP(final PluginWrapper wrapper) {
        super(wrapper);
        if ("DirectHTTP".equalsIgnoreCase(getHost())) {
            setConfigElements();
        }
    }

    private final String  SSLTRUSTALL                       = "SSLTRUSTALL";
    private final boolean SSLTRUSTAL_default                = true;
    private final String  AUTO_USE_FLASHGOT_REFERER         = "AUTO_USE_FLASHGOT_REFERER";
    private final boolean AUTO_USE_FLASHGOT_REFERER_default = true;

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), SSLTRUSTALL, JDL.L("plugins.hoster.http.ssltrustall", "Ignore SSL issues?")).setDefaultValue(SSLTRUSTAL_default));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), AUTO_USE_FLASHGOT_REFERER, "Auto use referer set by Flashgot?").setDefaultValue(AUTO_USE_FLASHGOT_REFERER_default));
    }

    private boolean isSSLTrustALL() {
        return SubConfiguration.getConfig("DirectHTTP").getBooleanProperty(SSLTRUSTALL, SSLTRUSTAL_default);
    }

    @Override
    public void correctDownloadLink(final DownloadLink link) {
        if (link.getDownloadURL().startsWith("directhttp")) {
            link.setUrlDownload(link.getDownloadURL().replaceAll("^directhttp://", ""));
        } else {
            link.setUrlDownload(link.getDownloadURL().replaceAll("httpviajd://", "http://").replaceAll("httpsviajd://", "https://"));
            /* this extension allows to manually add unknown extensions */
            link.setUrlDownload(link.getDownloadURL().replaceAll("\\.jdeatme$", ""));
        }
    }

    @Override
    public String getAGBLink() {
        return "";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    protected int getMaxSimultanDownload(DownloadLink link, Account account) {
        final int max = link != null ? link.getIntegerProperty(PROPERTY_MAX_CONCURRENT, -1) : -1;
        if (max > 0) {
            return max;
        } else {
            return super.getMaxSimultanDownload(link, account);
        }
    }

    @Override
    public Downloadable newDownloadable(DownloadLink downloadLink, Browser br) {
        final String host = Browser.getHost(downloadLink.getPluginPatternMatcher());
        if (StringUtils.contains(host, "mooo.com")) {
            final Browser brc = br.cloneBrowser();
            // clear referer required
            brc.setRequest(null);
            return super.newDownloadable(downloadLink, brc);
        } else {
            return super.newDownloadable(downloadLink, br);
        }
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception {
        if (this.requestFileInformation(downloadLink) == AvailableStatus.UNCHECKABLE) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 15 * 60 * 1000l);
        }
        /*
         * replace with br.setCurrentURL(null); in future (after 0.9)
         */
        final Cookies cookies = br.getCookies(getDownloadURL(downloadLink));
        br.setCurrentURL(null);
        br.setRequest(null);
        br.setCookies(getDownloadURL(downloadLink), cookies);
        this.br.getHeaders().put("Accept-Encoding", "identity");
        br.setDefaultSSLTrustALL(isSSLTrustALL());
        /* Workaround to clear referer */
        this.br.setFollowRedirects(true);
        this.br.setDebug(true);
        boolean resume = true;
        int chunks = 0;
        if (downloadLink.getBooleanProperty(DirectHTTP.NORESUME, false) || downloadLink.getBooleanProperty(DirectHTTP.FORCE_NORESUME, false)) {
            logger.info("Disable Resume:" + downloadLink.getBooleanProperty(DirectHTTP.NORESUME, false) + "|" + downloadLink.getBooleanProperty(DirectHTTP.FORCE_NORESUME, false));
            resume = false;
        }
        if (downloadLink.getBooleanProperty(DirectHTTP.NOCHUNKS, false) || downloadLink.getBooleanProperty(DirectHTTP.FORCE_NOCHUNKS, false) || resume == false) {
            logger.info("Disable Chunks:" + downloadLink.getBooleanProperty(DirectHTTP.NOCHUNKS, false) + "|" + downloadLink.getBooleanProperty(DirectHTTP.FORCE_NOCHUNKS, false) + "|" + resume);
            chunks = 1;
        }
        if (downloadLink.getBooleanProperty(FORCE_NOVERIFIEDFILESIZE, false)) {
            logger.info("Forced NoVerifiedFilesize! Disable Chunks/Resume!");
            chunks = 1;
            resume = false;
        }
        final String streamMod = downloadLink.getStringProperty("streamMod", null);
        if (streamMod != null) {
            logger.info("Apply streamMod handling:" + streamMod);
            resume = true;
            downloadLink.setProperty("ServerComaptibleForByteRangeRequest", true);
        }
        this.setCustomHeaders(this.br, downloadLink, null);
        if (resume && downloadLink.getVerifiedFileSize() > 0) {
            downloadLink.setProperty("ServerComaptibleForByteRangeRequest", true);
        } else {
            downloadLink.setProperty("ServerComaptibleForByteRangeRequest", Property.NULL);
        }
        final long downloadCurrentRaw = downloadLink.getDownloadCurrentRaw();
        if (downloadLink.getProperty(BYPASS_CLOUDFLARE_BGJ) != null) {
            logger.info("Apply Cloudflare BGJ bypass");
            resume = false;
            chunks = 1;
        }
        logger.info("ServerComaptibleForByteRangeRequest:" + downloadLink.getProperty("ServerComaptibleForByteRangeRequest"));
        try {
            if (downloadLink.getStringProperty("post", null) != null) {
                this.dl = new jd.plugins.BrowserAdapter().openDownload(this.br, downloadLink, getDownloadURL(downloadLink), downloadLink.getStringProperty("post", null), resume, chunks);
            } else {
                this.dl = new jd.plugins.BrowserAdapter().openDownload(this.br, downloadLink, getDownloadURL(downloadLink), resume, chunks);
            }
        } catch (final IllegalStateException e) {
            try {
                dl.getConnection().disconnect();
            } catch (final Throwable ignore) {
            }
            logger.log(e);
            if (StringUtils.containsIgnoreCase(e.getMessage(), "Range Error. Requested bytes=0- Got range: bytes 0-")) {
                logger.info("Workaround for Cloudflare-Cache transparent image compression!");
                downloadLink.setVerifiedFileSize(-1);
                throw new PluginException(LinkStatus.ERROR_RETRY, null, -1, e);
            } else {
                throw e;
            }
        }
        if (this.dl.getConnection().getResponseCode() == 403 && dl.getConnection().getRequestProperty("Range") != null) {
            followURLConnection(br, dl.getConnection());
            downloadLink.setProperty(DirectHTTP.NORESUME, Boolean.TRUE);
            throw new PluginException(LinkStatus.ERROR_RETRY);
        } else if (this.dl.getConnection().getResponseCode() == 503) {
            followURLConnection(br, dl.getConnection());
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 15 * 60 * 1000l);
        } else if ((dl.getConnection().getResponseCode() == 200 || dl.getConnection().getResponseCode() == 206) && dl.getConnection().getCompleteContentLength() == -1 && downloadLink.getVerifiedFileSize() > 0) {
            logger.info("Workaround for missing Content-Length!");
            dl.getConnection().disconnect();
            downloadLink.setVerifiedFileSize(-1);
            downloadLink.setProperty(DirectHTTP.FORCE_NOVERIFIEDFILESIZE, Boolean.TRUE);
            throw new PluginException(LinkStatus.ERROR_RETRY);
        }
        try {
            if (!this.dl.startDownload()) {
                if (this.dl.externalDownloadStop()) {
                    return;
                }
            }
        } catch (Exception e) {
            if (e instanceof PluginException) {
                if (((PluginException) e).getLinkStatus() == LinkStatus.ERROR_ALREADYEXISTS) {
                    throw e;
                }
                if (((PluginException) e).getLinkStatus() == LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE) {
                    throw e;
                }
            } else if (e instanceof SkipReasonException) {
                throw e;
            } else if (e instanceof InterruptedException) {
                throw e;
            }
            if (downloadLink.getLinkStatus().getErrorMessage() != null && downloadLink.getLinkStatus().getErrorMessage().startsWith(JDL.L("download.error.message.rangeheaders", "Server does not support chunkload")) || this.dl.getConnection().getResponseCode() == 400 && this.br.getRequest().getHttpConnection().getHeaderField("server").matches("HFS.+")) {
                if (downloadLink.getBooleanProperty(DirectHTTP.NORESUME, false) == false) {
                    /* clear chunkProgress and disable resume(ranges) and retry */
                    downloadLink.setChunksProgress(null);
                    downloadLink.setProperty(DirectHTTP.NORESUME, Boolean.TRUE);
                    throw new PluginException(LinkStatus.ERROR_RETRY, null, -1, e);
                }
            } else {
                if (downloadLink.getBooleanProperty(DirectHTTP.NOCHUNKS, false) == false) {
                    if (downloadLink.getDownloadCurrent() > downloadCurrentRaw + (128 * 1024l)) {
                        throw e;
                    } else {
                        /* disable multiple chunks => use only 1 chunk and retry */
                        downloadLink.setProperty(DirectHTTP.NOCHUNKS, Boolean.TRUE);
                        throw new PluginException(LinkStatus.ERROR_RETRY, null, -1, e);
                    }
                } else if (downloadLink.getBooleanProperty(DirectHTTP.NORESUME, false) == false) {
                    boolean disableRanges = false;
                    final long[] progress = downloadLink.getChunksProgress();
                    if (progress != null) {
                        if (progress.length > 1) {
                            /* reset chunkProgress to first chunk and retry */
                            downloadLink.setChunksProgress(new long[] { progress[0] });
                            throw new PluginException(LinkStatus.ERROR_RETRY, null, -1, e);
                        } else {
                            if (downloadLink.getDownloadCurrent() == downloadCurrentRaw) {
                                disableRanges = true;
                            }
                        }
                    } else {
                        disableRanges = true;
                    }
                    if (disableRanges) {
                        /* clear chunkProgress and disable resume(ranges) and retry */
                        downloadLink.setChunksProgress(null);
                        downloadLink.setProperty(DirectHTTP.NORESUME, Boolean.valueOf(true));
                        throw new PluginException(LinkStatus.ERROR_RETRY, null, -1, e);
                    }
                }
            }
            throw e;
        }
    }

    private String customDownloadURL = null;

    private void setDownloadURL(String newURL, DownloadLink link) throws IOException {
        if (link != null && !StringUtils.equals(link.getDownloadURL(), newURL)) {
            link.setUrlDownload(newURL);
            this.customDownloadURL = null;
        } else {
            this.customDownloadURL = newURL;
        }
    }

    private boolean hasCustomDownloadURL() {
        return customDownloadURL != null;
    }

    private String getDownloadURL(DownloadLink downloadLink) throws IOException {
        String ret = customDownloadURL;
        if (ret == null) {
            ret = downloadLink.getDownloadURL();
        }
        if (downloadLink.getProperty(BYPASS_CLOUDFLARE_BGJ) != null) {
            ret = URLHelper.parseLocation(new URL(ret), "&bpcfbgj=" + System.nanoTime());
        }
        return ret;
    }

    private URLConnectionAdapter prepareConnection(final Browser br, final DownloadLink downloadLink, final Set<String> optionSet) throws Exception {
        return prepareConnection(br, downloadLink, 1, this.preferHeadRequest, optionSet == null ? new HashSet<String>() : optionSet);
    }

    private URLConnectionAdapter prepareConnection(final Browser br, final DownloadLink downloadLink, final int round, boolean preferHeadRequest, Set<String> optionSet) throws Exception {
        URLConnectionAdapter urlConnection = null;
        br.setRequest(null);
        this.setCustomHeaders(br, downloadLink, optionSet);
        boolean rangeHeader = false;
        try {
            String downloadURL = getDownloadURL(downloadLink);
            if (downloadLink.getProperty("streamMod") != null || optionSet.contains("streamMod")) {
                rangeHeader = true;
                br.getHeaders().put("Range", "bytes=" + 0 + "-");
            }
            if (downloadLink.getStringProperty("post", null) != null) {
                urlConnection = openAntiDDoSRequestConnection(br, br.createPostRequest(downloadURL, downloadLink.getStringProperty("post", null)));
            } else {
                try {
                    if ("GET".equals(downloadLink.getStringProperty(PROPERTY_REQUEST_TYPE, null))) {
                        // PROPERTY_REQUEST_TYPE is set to GET
                        urlConnection = openAntiDDoSRequestConnection(br, br.createGetRequest(downloadURL));
                    } else if (preferHeadRequest || "HEAD".equals(downloadLink.getStringProperty(PROPERTY_REQUEST_TYPE, null))) {
                        // PROPERTY_REQUEST_TYPE is set to HEAD or preferHeadRequest
                        urlConnection = openAntiDDoSRequestConnection(br, br.createHeadRequest(downloadURL));
                    } else {
                        urlConnection = openAntiDDoSRequestConnection(br, br.createGetRequest(downloadURL));
                    }
                    if (urlConnection.getResponseCode() == 403 || urlConnection.getResponseCode() == 405) {
                        boolean retry = false;
                        if (downloadLink.getStringProperty(LinkCrawler.PROPERTY_AUTO_REFERER, null) != null && optionSet.add(PROPERTY_DISABLE_PREFIX + LinkCrawler.PROPERTY_AUTO_REFERER)) {
                            retry = true;
                        } else if (optionSet.add(PROPERTY_ENABLE_PREFIX + "selfReferer")) {
                            retry = true;
                        }
                        if (retry) {
                            followURLConnection(br, urlConnection);
                            return prepareConnection(br, downloadLink, round + 1, preferHeadRequest, optionSet);
                        }
                    }
                    if (RequestMethod.HEAD.equals(urlConnection.getRequestMethod())) {
                        if (urlConnection.getResponseCode() == 404) {
                            /*
                             * && StringUtils.contains(urlConnection.getHeaderField("Cache-Control"), "must-revalidate") &&
                             * urlConnection.getHeaderField("Via") != null
                             */
                            followURLConnection(br, urlConnection);
                            return prepareConnection(br, downloadLink, round + 1, false, optionSet);
                        } else if (urlConnection.getResponseCode() != 401 && urlConnection.getResponseCode() >= 300) {
                            // no head support?
                            followURLConnection(br, urlConnection);
                            return prepareConnection(br, downloadLink, round + 1, false, optionSet);
                        }
                    }
                } catch (final IOException e) {
                    followURLConnection(br, urlConnection);
                    if (preferHeadRequest || "HEAD".equals(downloadLink.getStringProperty(PROPERTY_REQUEST_TYPE, null))) {
                        /* some servers do not allow head requests */
                        try {
                            urlConnection = openAntiDDoSRequestConnection(br, br.createGetRequest(downloadURL));
                            downloadLink.setProperty(PROPERTY_REQUEST_TYPE, "GET");
                        } catch (IOException e2) {
                            followURLConnection(br, urlConnection);
                            if (StringUtils.startsWithCaseInsensitive(downloadURL, "http://")) {
                                setDownloadURL(downloadURL.replaceFirst("(?i)^http://", "https://"), null);
                                return prepareConnection(br, downloadLink, round + 1, preferHeadRequest, optionSet);
                            } else {
                                throw e2;
                            }
                        }
                    } else {
                        if (StringUtils.startsWithCaseInsensitive(downloadURL, "http://")) {
                            setDownloadURL(downloadURL.replaceFirst("(?i)^http://", "https://"), null);
                            return prepareConnection(br, downloadLink, round + 1, preferHeadRequest, optionSet);
                        } else {
                            throw e;
                        }
                    }
                }
            }
        } finally {
            if (rangeHeader) {
                br.getHeaders().remove("Range");
            }
        }
        return urlConnection;
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.GENERIC };
    }

    private boolean preferHeadRequest = true;

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        return requestFileInformation(downloadLink, 0);
    }

    private void followURLConnection(Browser br, URLConnectionAdapter urlConnection) {
        if (urlConnection != null) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            } finally {
                urlConnection.disconnect();
            }
        }
    }

    private boolean retryConnection(final DownloadLink downloadLink, final URLConnectionAdapter con) {
        switch (con.getResponseCode()) {
        case 400:// Bad Request
        case 401:// Unauthorized
        case 403:// Forbidden
        case 404:// Not found
        case 410:// Gone
        case 470:// special response code, see thread 81171
            return downloadLink.getStringProperty(DirectHTTP.POSSIBLE_URLPARAM, null) != null || RequestMethod.HEAD.equals(con.getRequest().getRequestMethod());
        default:
            return false;
        }
    }

    private boolean isDirectHTTPRule(final DownloadLink downloadLink) {
        long linkCrawlerRuleID = -1;
        if ((linkCrawlerRuleID = downloadLink.getLongProperty("lcrID", -1)) != -1) {
            final LinkCrawlerRule rule = LinkCrawler.getLinkCrawlerRule(linkCrawlerRuleID);
            return rule != null && LinkCrawlerRule.RULE.DIRECTHTTP.equals(rule.getRule());
        } else {
            return false;
        }
    }

    private void applyRateLimits(final DownloadLink downloadLink, Browser br) {
        int limit = downloadLink != null ? downloadLink.getIntegerProperty(PROPERTY_RATE_LIMIT_TLD) : -1;
        if (limit > 0) {
            final String host = Browser.getHost(downloadLink.getPluginPatternMatcher(), false);
            logger.info("Apply RateLimit:" + host + "|" + limit);
            Browser.setRequestIntervalLimitGlobal(host, false, limit);
        }
        limit = downloadLink != null ? downloadLink.getIntegerProperty(PROPERTY_RATE_LIMIT) : -1;
        if (limit > 0) {
            final String host = Browser.getHost(downloadLink.getPluginPatternMatcher(), true);
            logger.info("Apply RateLimit:" + host + "|" + limit);
            Browser.setRequestIntervalLimitGlobal(host, true, limit);
        }
    }

    private String preSetFinalName = null;
    private String preSetFIXNAME   = null;

    private AvailableStatus requestFileInformation(final DownloadLink downloadLink, int retry) throws Exception {
        final HashSet<String> optionSet = downloadLink.getObjectProperty(PROPERTY_OPTION_SET, TypeRef.STRING_HASHSET);
        return requestFileInformation(downloadLink, 0, optionSet);
    }

    private AvailableStatus requestFileInformation(final DownloadLink downloadLink, int retry, Set<String> optionSet) throws Exception {
        if (optionSet == null) {
            optionSet = new HashSet<String>();
            downloadLink.setProperty(PROPERTY_OPTION_SET, optionSet);
        }
        if (retry == 0) {
            preSetFinalName = downloadLink.getFinalFileName();
            preSetFIXNAME = downloadLink.getStringProperty(FIXNAME, null);
        }
        if (downloadLink.getBooleanProperty("OFFLINE", false) || downloadLink.getBooleanProperty("offline", false)) {
            // used to make offline links for decrypters. To prevent 'Checking online status' and/or prevent downloads of downloadLink.
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (retry == 5) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        applyRateLimits(downloadLink, br);
        final int ioExceptions = downloadLink.getIntegerProperty(IOEXCEPTIONS, 0);
        downloadLink.removeProperty(IOEXCEPTIONS);
        // if (true) throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, 60 * 1000l);
        this.setBrowserExclusive();
        this.br.setDefaultSSLTrustALL(isSSLTrustALL());
        this.br.getHeaders().put("Accept-Encoding", "identity");
        final List<AuthenticationFactory> authenticationFactories = new ArrayList<AuthenticationFactory>();
        final URL url = new URL(getDownloadURL(downloadLink));
        if (url.getUserInfo() != null) {
            authenticationFactories.add(new URLUserInfoAuthentication());
        }
        authenticationFactories.addAll(AuthenticationController.getInstance().getSortedAuthenticationFactories(url, null));
        authenticationFactories.add(new CallbackAuthenticationFactory() {
            protected Authentication remember = null;

            protected Authentication askAuthentication(Browser browser, Request request, final String realm) {
                try {
                    final Login login = requestLogins(org.jdownloader.translate._JDT.T.DirectHTTP_getBasicAuth_message(), realm, downloadLink);
                    if (login != null) {
                        final Authentication ret = new DefaultAuthenticanFactory(request.getURL().getHost(), realm, login.getUsername(), login.getPassword()).buildAuthentication(browser, request);
                        addAuthentication(ret);
                        if (login.isRememberSelected()) {
                            remember = ret;
                        }
                        return ret;
                    }
                } catch (PluginException e) {
                    getLogger().log(e);
                }
                return null;
            }

            @Override
            public boolean retry(Authentication authentication, Browser browser, Request request) {
                if (authentication != null && containsAuthentication(authentication) && request.getAuthentication() == authentication && !requiresAuthentication(request)) {
                    if (remember == authentication) {
                        final AuthenticationInfo auth = new AuthenticationInfo();
                        auth.setRealm(authentication.getRealm());
                        auth.setUsername(authentication.getUsername());
                        auth.setPassword(authentication.getPassword());
                        auth.setHostmask(authentication.getHost());
                        auth.setType(Type.HTTP);
                        AuthenticationController.getInstance().add(auth);
                    } else {
                        try {
                            final String newURL = authentication.getURLWithUserInfo(url);
                            if (newURL != null) {
                                downloadLink.setUrlDownload(newURL);
                            }
                        } catch (IOException e) {
                            getLogger().log(e);
                        }
                    }
                }
                return super.retry(authentication, browser, request);
            }
        });
        this.br.setFollowRedirects(true);
        URLConnectionAdapter urlConnection = null;
        try {
            String basicauth = null;
            for (final AuthenticationFactory authenticationFactory : authenticationFactories) {
                br.setCustomAuthenticationFactory(authenticationFactory);
                urlConnection = this.prepareConnection(this.br, downloadLink, optionSet);
                logger.info("looksLikeDownloadableContent result(" + retry + ",1):" + looksLikeDownloadableContent(urlConnection));
                if (isCustomOffline(urlConnection)) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                if (retryConnection(downloadLink, urlConnection) || (StringUtils.contains(urlConnection.getContentType(), "image") && (urlConnection.getLongContentLength() < 1024) || StringUtils.containsIgnoreCase(getFileNameFromHeader(urlConnection), "expired"))) {
                    if (downloadLink.getStringProperty(DirectHTTP.POSSIBLE_URLPARAM, null) != null || RequestMethod.HEAD.equals(urlConnection.getRequest().getRequestMethod())) {
                        /* check if we need the URLPARAMS to download the file */
                        followURLConnection(br, urlConnection);
                        if (RequestMethod.HEAD.equals(urlConnection.getRequest().getRequestMethod())) {
                            preferHeadRequest = false;
                        }
                        if (downloadLink.getStringProperty(DirectHTTP.POSSIBLE_URLPARAM, null) != null) {
                            final String newURL = getDownloadURL(downloadLink) + downloadLink.getStringProperty(DirectHTTP.POSSIBLE_URLPARAM, null);
                            downloadLink.removeProperty(DirectHTTP.POSSIBLE_URLPARAM);
                            setDownloadURL(newURL, downloadLink);
                        }
                        br.setRequest(null);
                        urlConnection = this.prepareConnection(this.br, downloadLink, optionSet);
                        logger.info("looksLikeDownloadableContent result(" + retry + ",2):" + looksLikeDownloadableContent(urlConnection));
                        if (isCustomOffline(urlConnection)) {
                            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                        }
                    }
                }
                if (urlConnection.getResponseCode() == 401) {
                    followURLConnection(br, urlConnection);
                    if (urlConnection.getHeaderField(HTTPConstants.HEADER_RESPONSE_WWW_AUTHENTICATE) == null) {
                        /* no basic auth */
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                } else {
                    break;
                }
            }
            if (urlConnection.getResponseCode() == 503 || urlConnection.getResponseCode() == 504 || urlConnection.getResponseCode() == 521) {
                followURLConnection(br, urlConnection);
                return AvailableStatus.UNCHECKABLE;
            } else if (urlConnection.getResponseCode() == 404 || urlConnection.getResponseCode() == 451 || urlConnection.getResponseCode() == 410 || !urlConnection.isOK()) {
                followURLConnection(br, urlConnection);
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            downloadLink.setProperty("auth", basicauth);
            final String contentType = urlConnection.getContentType();
            if (contentType != null) {
                if (StringUtils.startsWithCaseInsensitive(contentType, "application/pls") && StringUtils.endsWithCaseInsensitive(urlConnection.getURL().getPath(), ".mp3")) {
                    followURLConnection(br, urlConnection);
                    final String mp3URL = this.br.getRegex("(https?://.*?\\.mp3)").getMatch(0);
                    if (hasCustomDownloadURL()) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                    if (mp3URL != null) {
                        setDownloadURL(mp3URL, null);
                        return this.requestFileInformation(downloadLink, retry + 1, optionSet);
                    }
                }
            }
            final long length = urlConnection.getLongContentLength();
            if (length == 0 && RequestMethod.HEAD.equals(urlConnection.getRequest().getRequestMethod())) {
                preferHeadRequest = false;
                followURLConnection(br, urlConnection);
                return this.requestFileInformation(downloadLink, retry + 1, optionSet);
            }
            if (urlConnection.getHeaderField("cf-bgj") != null && !downloadLink.hasProperty(BYPASS_CLOUDFLARE_BGJ)) {
                if (RequestMethod.HEAD.equals(urlConnection.getRequest().getRequestMethod())) {
                    followURLConnection(br, urlConnection);
                } else {
                    urlConnection.disconnect();
                }
                downloadLink.setProperty(BYPASS_CLOUDFLARE_BGJ, Boolean.TRUE);
                return this.requestFileInformation(downloadLink, retry + 1, optionSet);
            }
            String streamMod = null;
            for (Entry<String, List<String>> header : urlConnection.getHeaderFields().entrySet()) {
                if (StringUtils.startsWithCaseInsensitive(header.getKey(), "X-Mod-H264-Streaming")) {
                    streamMod = header.getKey();
                } else if (StringUtils.startsWithCaseInsensitive(header.getKey(), "x-swarmify")) {
                    streamMod = header.getKey();
                }
            }
            if (streamMod != null && downloadLink.getProperty("streamMod") == null) {
                downloadLink.setProperty("streamMod", streamMod);
                if (RequestMethod.HEAD.equals(urlConnection.getRequest().getRequestMethod())) {
                    followURLConnection(br, urlConnection);
                } else {
                    urlConnection.disconnect();
                }
                return this.requestFileInformation(downloadLink, retry + 1, optionSet);
            }
            if (contentType != null && (contentType.startsWith("text/html") || contentType.startsWith("application/json")) && urlConnection.isContentDisposition() == false && downloadLink.getBooleanProperty(DirectHTTP.TRY_ALL, false) == false && !isDirectHTTPRule(downloadLink)) {
                /* jd does not want to download html content! */
                /* if this page does redirect via js/html, try to follow */
                if (RequestMethod.HEAD.equals(urlConnection.getRequest().getRequestMethod())) {
                    preferHeadRequest = false;
                    followURLConnection(br, urlConnection);
                    return this.requestFileInformation(downloadLink, retry + 1, optionSet);
                } else {
                    final String pageContent = this.br.followConnection(true);
                    if (StringUtils.endsWithCaseInsensitive(br.getURL(), "mp4")) {
                        final String videoURL = br.getRegex("source type=\"video/mp4\"\\s*src=\"(https?://.*)\"").getMatch(0);
                        if (videoURL != null && !hasCustomDownloadURL()) {
                            setDownloadURL(videoURL, null);
                            return AvailableStatus.TRUE;
                            // return this.requestFileInformation(downloadLink);
                        }
                    }
                    if (hasCustomDownloadURL()) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                    /* search urls */
                    final ArrayList<String> embeddedLinks = DirectHTTP.findUrls(pageContent);
                    String embeddedLink = null;
                    if (embeddedLinks.size() == 1) {
                        embeddedLink = embeddedLinks.get(0);
                    } else {
                        final String orginalURL = getDownloadURL(downloadLink);
                        final String extension = Files.getExtension(orginalURL);
                        if (embeddedLinks.contains(orginalURL)) {
                            embeddedLink = orginalURL;
                        } else {
                            for (final String check : embeddedLinks) {
                                if (StringUtils.endsWithCaseInsensitive(check, extension)) {
                                    if (embeddedLink == null) {
                                        embeddedLink = check;
                                    } else {
                                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                                    }
                                }
                            }
                        }
                        if (embeddedLink == null) {
                            final String urlParams = downloadLink.getStringProperty(DirectHTTP.POSSIBLE_URLPARAM, null);
                            if (urlParams != null) {
                                downloadLink.setProperty(DirectHTTP.POSSIBLE_URLPARAM, Property.NULL);
                                final String newURL = getDownloadURL(downloadLink) + urlParams;
                                setDownloadURL(newURL, downloadLink);
                                return this.requestFileInformation(downloadLink, retry + 1, optionSet);
                            }
                            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                        }
                    }
                    /* found one valid url */
                    setDownloadURL(embeddedLink, downloadLink);
                    return this.requestFileInformation(downloadLink, retry + 1, optionSet);
                }
            } else {
                if (RequestMethod.HEAD.equals(urlConnection.getRequest().getRequestMethod())) {
                    followURLConnection(br, urlConnection);
                } else {
                    urlConnection.disconnect();
                }
            }
            /* if final filename already set, do not change */
            if (downloadLink.getFinalFileName() == null) {
                /* Restore filename from property */
                String fileName = downloadLink.getStringProperty(FIXNAME, null);
                if (fileName == null && downloadLink.getBooleanProperty("MOVIE2K", false)) {
                    final String ext = new Regex(contentType, "(audio|video)/(x\\-)?(.*?)$").getMatch(2);
                    fileName = downloadLink.getName() + "." + ext;
                }
                if (fileName == null) {
                    final DispositionHeader dispositionHeader = Plugin.parseDispositionHeader(urlConnection);
                    if (dispositionHeader != null && StringUtils.isNotEmpty(dispositionHeader.getFilename())) {
                        fileName = dispositionHeader.getFilename();
                        if (dispositionHeader.getEncoding() == null) {
                            try {
                                fileName = URLEncode.decodeURIComponent(dispositionHeader.getFilename(), "UTF-8", true);
                            } catch (final IllegalArgumentException ignore) {
                                logger.log(ignore);
                            } catch (final UnsupportedEncodingException ignore) {
                                logger.log(ignore);
                            }
                        }
                    }
                    if (fileName == null) {
                        fileName = Plugin.extractFileNameFromURL(urlConnection.getRequest().getUrl());
                        if (fileName != null) {
                            if (StringUtils.equalsIgnoreCase("php", Files.getExtension(fileName)) || fileName.matches(IP.IP_PATTERN)) {
                                fileName = null;
                            }
                        }
                    }
                    if (fileName != null && downloadLink.getBooleanProperty("urlDecodeFinalFileName", true)) {
                        fileName = SimpleFTP.BestEncodingGuessingURLDecode(fileName);
                    }
                }
                if (fileName == null) {
                    fileName = downloadLink.getName();
                }
                if (fileName != null) {
                    if (fileName.indexOf(".") < 0) {
                        final String ext = getExtensionFromMimeType(contentType);
                        if (ext != null) {
                            fileName = fileName + "." + ext;
                        }
                    }
                    downloadLink.setFinalFileName(fileName);
                    /* save filename in property so we can restore in reset case */
                    downloadLink.setProperty(FIXNAME, fileName);
                }
            }
            if (length >= 0) {
                downloadLink.setDownloadSize(length);
                final String contentEncoding = urlConnection.getHeaderField("Content-Encoding");
                if (urlConnection.getHeaderField("X-Mod-H264-Streaming") == null && (contentEncoding == null || "none".equalsIgnoreCase(contentEncoding))) {
                    final String contentMD5 = urlConnection.getHeaderField("Content-MD5");
                    final String contentSHA1 = urlConnection.getHeaderField("Content-SHA1");
                    if (downloadLink.getSha1Hash() == null) {
                        if (contentSHA1 != null) {
                            downloadLink.setSha1Hash(contentSHA1);
                        }
                    } else if (downloadLink.getMD5Hash() == null) {
                        if (contentMD5 != null) {
                            downloadLink.setMD5Hash(contentMD5);
                        }
                    }
                    if (downloadLink.getBooleanProperty(FORCE_NOVERIFIEDFILESIZE, false)) {
                        logger.info("Forced NoVerifiedFileSize:" + length);
                        downloadLink.setVerifiedFileSize(-1);
                    } else {
                        downloadLink.setVerifiedFileSize(length);
                    }
                }
            } else {
                downloadLink.setDownloadSize(-1);
                downloadLink.setVerifiedFileSize(-1);
            }
            final String referer = urlConnection.getRequestProperty(HTTPConstants.HEADER_REQUEST_REFERER);
            downloadLink.setProperty("lastRefURL", referer);
            final RequestMethod requestMethod = urlConnection.getRequestMethod();
            downloadLink.setProperty("allowOrigin", urlConnection.getHeaderField("access-control-allow-origin"));
            downloadLink.removeProperty(IOEXCEPTIONS);
            AvailableStatus status = AvailableStatus.TRUE;
            if (RequestMethod.HEAD.equals(requestMethod)) {
                if (downloadLink.getStringProperty(PROPERTY_REQUEST_TYPE, null) == null) {
                    final String headFinalFileName = downloadLink.getFinalFileName();
                    final String headFIXNAME = downloadLink.getStringProperty(FIXNAME, null);
                    final long headFileSize = downloadLink.getVerifiedFileSize();
                    boolean trustHeadRequest = true;
                    final boolean preferHeadRequest = this.preferHeadRequest;
                    try {
                        // trust preset FinalFileName
                        downloadLink.setFinalFileName(preSetFinalName);
                        // trust preset FIXNAME property
                        downloadLink.setProperty(FIXNAME, preSetFIXNAME);
                        downloadLink.setVerifiedFileSize(-1);
                        this.preferHeadRequest = false;
                        status = this.requestFileInformation(downloadLink, retry + 1, optionSet);
                        if (AvailableStatus.TRUE.equals(status)) {
                            if (headFileSize != downloadLink.getVerifiedFileSize()) {
                                logger.info("Don't trust head request: contentLength mismatch! head:" + headFileSize + "!=get:" + downloadLink.getVerifiedFileSize());
                                trustHeadRequest = false;
                            }
                            if (preSetFinalName == null && !StringUtils.equals(headFinalFileName, downloadLink.getFinalFileName())) {
                                logger.info("Don't trust head request: name mismatch! head:" + headFinalFileName + "!=get:" + downloadLink.getFinalFileName());
                                trustHeadRequest = false;
                            }
                        }
                    } finally {
                        this.preferHeadRequest = preferHeadRequest;
                        if (trustHeadRequest) {
                            logger.info("Trust head request(validated)!");
                            downloadLink.setProperty(PROPERTY_REQUEST_TYPE, requestMethod.name());
                            downloadLink.setVerifiedFileSize(headFileSize);
                            if (preSetFinalName == null) {
                                downloadLink.setFinalFileName(headFinalFileName);
                            }
                            if (preSetFIXNAME == null) {
                                downloadLink.setProperty(FIXNAME, headFIXNAME);
                            }
                        }
                    }
                } else {
                    logger.info("Trust head request(stored)!");
                }
            } else {
                downloadLink.setProperty(PROPERTY_REQUEST_TYPE, requestMethod.name());
            }
            return status;
        } catch (final PluginException e2) {
            /* try referer set by flashgot and check if it works then */
            if (downloadLink.getBooleanProperty("tryoldref", false) == false && downloadLink.getStringProperty("referer", null) != null) {
                downloadLink.setProperty("tryoldref", true);
                return this.requestFileInformation(downloadLink, retry + 1, optionSet);
            } else {
                final String finalFileName = downloadLink.getFinalFileName();
                resetDownloadlink(downloadLink);
                if (finalFileName != null) {
                    downloadLink.setFinalFileName(finalFileName);
                }
                throw e2;
            }
        } catch (IOException e) {
            logger.log(e);
            final String finalFileName = downloadLink.getFinalFileName();
            resetDownloadlink(downloadLink);
            if (finalFileName != null) {
                downloadLink.setFinalFileName(finalFileName);
            }
            if (!isConnectionOffline(e)) {
                final int nextIOExceptions = ioExceptions + 1;
                if (nextIOExceptions > 2) {
                    if (e instanceof java.net.ConnectException || e.getCause() instanceof java.net.ConnectException) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND, null, -1, e);
                    }
                    if (e instanceof java.net.UnknownHostException || e.getCause() instanceof java.net.UnknownHostException) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND, null, -1, e);
                    }
                }
                downloadLink.setProperty(IOEXCEPTIONS, nextIOExceptions);
            }
            String message = e.getMessage();
            if (message == null && e.getCause() != null) {
                message = e.getCause().getMessage();
            }
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Network problem: " + message, 30 * 60 * 1000l, e);
        } catch (final Exception e) {
            this.logger.log(e);
        } finally {
            try {
                urlConnection.disconnect();
            } catch (final Throwable e) {
            }
        }
        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
    }

    private final String IOEXCEPTIONS = "IOEXCEPTIONS";

    @Override
    public void reset() {
        preferHeadRequest = true;
        customDownloadURL = null;
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
        link.removeProperty(IOEXCEPTIONS);
        link.removeProperty(DirectHTTP.NORESUME);
        link.removeProperty(DirectHTTP.NOCHUNKS);
        link.removeProperty("lastRefURL");
        link.removeProperty(PROPERTY_REQUEST_TYPE);
        link.removeProperty("streamMod");
        link.removeProperty("allowOrigin");
        link.removeProperty(PROPERTY_OPTION_SET);
        link.removeProperty(FORCE_NOVERIFIEDFILESIZE);
        link.removeProperty(BYPASS_CLOUDFLARE_BGJ);
        /* E.g. filename set in crawler --> We don't want to lose that. */
        final String fixName = link.getStringProperty(FIXNAME, null);
        if (StringUtils.isNotEmpty(fixName)) {
            link.setFinalFileName(fixName);
        }
    }

    @Override
    public void resetPluginGlobals() {
    }

    private void setCustomHeaders(final Browser br, final DownloadLink downloadLink, Set<String> optionSet) throws IOException {
        /* allow customized headers, eg useragent */
        final Object customRet = downloadLink.getProperty("customHeader");
        List<String[]> custom = null;
        if (customRet != null && customRet instanceof List) {
            custom = (List<String[]>) customRet;
        }
        // Bla
        if (custom != null && custom.size() > 0) {
            for (final Object header : custom) {
                /*
                 * this is needed because we no longer serialize the stuff. We use JSON as storage and it does not differ between String[]
                 * and ArrayList<String>
                 */
                if (header instanceof ArrayList) {
                    br.getHeaders().put((String) ((ArrayList<?>) header).get(0), (String) ((ArrayList<?>) header).get(1));
                } else if (header.getClass().isArray()) {
                    br.getHeaders().put(((String[]) header)[0], ((String[]) header)[1]);
                }
            }
        }
        /*
         * seems like flashgot catches the wrong referer and some downloads do not work then, we do not set referer as a workaround
         */
        if (getValidReferer(downloadLink.getStringProperty("refURL", null)) != null) {
            /* refURL is for internal use */
            br.getHeaders().put("Referer", downloadLink.getStringProperty("refURL", null));
        }
        /*
         * try the referer set by flashgot, maybe it works
         */
        if ((getPluginConfig().getBooleanProperty(AUTO_USE_FLASHGOT_REFERER, AUTO_USE_FLASHGOT_REFERER_default) || downloadLink.getBooleanProperty("tryoldref", false)) && getValidReferer(downloadLink.getStringProperty("referer", null)) != null) {
            /* refURL is for internal use */
            downloadLink.setProperty("tryoldref", true);
            br.getHeaders().put("Referer", downloadLink.getStringProperty("referer", null));
        }
        if (downloadLink.getStringProperty(DirectHTTP.PROPERTY_COOKIES, null) != null) {
            br.getCookies(getDownloadURL(downloadLink)).add(Cookies.parseCookies(downloadLink.getStringProperty(DirectHTTP.PROPERTY_COOKIES, null), Browser.getHost(getDownloadURL(downloadLink)), null));
        }
        long linkCrawlerRuleID = -1;
        if ((linkCrawlerRuleID = downloadLink.getLongProperty("lcrID", -1)) != -1) {
            final List<String[]> linkCrawlerRuleCookies = LinkCrawler.getLinkCrawlerRuleCookies(linkCrawlerRuleID, true);
            if (linkCrawlerRuleCookies != null && linkCrawlerRuleCookies.size() > 0) {
                final String url = getDownloadURL(downloadLink);
                for (final String cookie[] : linkCrawlerRuleCookies) {
                    br.setCookie(url, cookie[0], cookie[1]);
                }
            }
        }
        if (getValidReferer(downloadLink.getStringProperty("Referer", null)) != null) {
            // used in MANY plugins!
            br.getHeaders().put("Referer", downloadLink.getStringProperty("Referer", null));
        }
        if (getValidReferer(downloadLink.getStringProperty("lastRefURL", null)) != null) {
            // used in MANY plugins!
            br.getHeaders().put("Referer", downloadLink.getStringProperty("lastRefURL", null));
        }
        if (!br.getHeaders().contains("Referer") && (optionSet == null || !optionSet.contains(PROPERTY_DISABLE_PREFIX + LinkCrawler.PROPERTY_AUTO_REFERER))) {
            if (getValidReferer(downloadLink.getStringProperty(LinkCrawler.PROPERTY_AUTO_REFERER, null)) != null) {
                br.getHeaders().put("Referer", downloadLink.getStringProperty(LinkCrawler.PROPERTY_AUTO_REFERER, null));
            }
        }
        if (optionSet != null && optionSet.contains(PROPERTY_ENABLE_PREFIX + "selfReferer")) {
            final String referer = getDownloadURL(downloadLink);
            br.getHeaders().put("Referer", referer);
        }
        this.downloadWorkaround(br, downloadLink);
        if (downloadLink.hasProperty("allowOrigin")) {
            final String referer = br.getHeaders().get("Referer");
            if (referer != null) {
                final URL refURL = new URL(referer);
                br.getHeaders().put("Origin", refURL.getProtocol() + "://" + refURL.getHost());
            } else {
                br.getHeaders().put("Origin", "*");
            }
        }
    }

    protected String getValidReferer(final String referer) {
        if (StringUtils.startsWithCaseInsensitive(referer, "http://") || StringUtils.startsWithCaseInsensitive(referer, "https://")) {
            return referer;
        } else {
            return null;
        }
    }

    protected void downloadWorkaround(final Browser br, final DownloadLink downloadLink) throws IOException {
        // we shouldn't potentially over right setCustomHeaders..
        if (br.getHeaders().get("Referer") == null) {
            final String link = getDownloadURL(downloadLink);
            if (link.contains("sites.google.com")) {
                /*
                 * It seems google checks referer and ip must have called the page lately. TODO: 2021-12-07 Check if this is still required
                 */
                br.getHeaders().put("Referer", "https://sites.google.com");
            }
        }
    }

    @Override
    protected void updateDownloadLink(final CheckableLink checkableLink, final String url) {
        final DownloadLink downloadLink = checkableLink != null ? checkableLink.getDownloadLink() : null;
        if (downloadLink != null) {
            final List<DownloadLink> downloadLinks = getDownloadLinks(url, null);
            if (downloadLinks != null && downloadLinks.size() == 1) {
                downloadLink.setPluginPatternMatcher(downloadLinks.get(0).getPluginPatternMatcher());
            } else {
                downloadLink.setPluginPatternMatcher(url);
            }
            downloadLink.setDomainInfo(null);
            downloadLink.resume(Arrays.asList(new PluginForHost[] { this }));
            preProcessDirectHTTP(downloadLink, url);
            final LinkChecker<CheckableLink> linkChecker = new LinkChecker<CheckableLink>(true);
            linkChecker.check(checkableLink);
        }
    }

    /**
     * custom offline references based on conditions found within previous URLConnectionAdapter request.
     *
     * @author raztoki
     */
    private boolean isCustomOffline(URLConnectionAdapter urlConnection) {
        return false;
    }

    @Override
    public boolean hasCaptcha(final DownloadLink link, final jd.plugins.Account acc) {
        return false;
    }

    @Override
    protected boolean supportsUpdateDownloadLink(CheckableLink checkableLink) {
        return checkableLink != null && checkableLink.getDownloadLink() != null;
    }

    @Override
    public String getHost(final DownloadLink link, Account account) {
        if (link != null) {
            /* prefer domain via public suffic list */
            return Browser.getHost(link.getDownloadURL());
        } else if (account != null) {
            return account.getHoster();
        } else {
            return null;
        }
    }

    @Override
    public Boolean siteTesterDisabled() {
        return Boolean.TRUE;
    }
}