package jd.plugins.hoster;

import jd.PluginWrapper;
import jd.controlling.linkcrawler.CheckableLink;
import jd.http.URLConnectionAdapter;
import jd.nutils.SimpleFTP;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;
import jd.utils.locale.JDL;

import org.appwork.net.protocol.http.HTTPConstants;
import org.appwork.utils.Files;
import org.appwork.utils.StringUtils;
import org.appwork.utils.net.httpconnection.HTTPConnectionUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter.ExtensionsFilterInterface;
import org.jdownloader.plugins.components.RefreshSessionLink;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "video.google.com" }, urls = { "http://(www\\.)?video\\.google\\.(com|de)/(videoplay\\?docid=|googleplayer\\.swf\\?autoplay=1\\&fs=true\\&fs=true\\&docId=)(\\-)?\\d+|https?://[\\w\\-]+\\.googlevideo\\.com/videoplayback\\?.+|https?://(?!translate\\.)\\w+\\.googleusercontent\\.com/.+|https?://[\\w\\-\\.]+drive\\.google\\.com/videoplayback\\?.+" })
public class VideoGoogle extends PluginForHost {
    private String       dllink = null;
    private final String embed  = "https?://[\\w\\-]+\\.googlevideo\\.com/videoplayback\\?.+|https?://\\w+\\.googleusercontent\\.com/.+|https?://[\\w\\-\\.]+drive\\.google\\.com/videoplayback\\?.+";

    public VideoGoogle(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.google.com/accounts/TOS?loc=ZZ&hl=en";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    protected boolean supportsUpdateDownloadLink(CheckableLink checkableLink) {
        return checkableLink != null && checkableLink.getDownloadLink() != null && StringUtils.contains(checkableLink.getDownloadLink().getPluginPatternMatcher(), "videoplayback?");
    }

    public void correctDownloadLink(final DownloadLink link) {
        if (!link.getDownloadURL().matches(embed)) {
            link.setUrlDownload("http://video.google.com/videoplay?docid=" + new Regex(link.getDownloadURL(), "((\\-)?\\d+)$").getMatch(0));
        }
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        dl = new jd.plugins.BrowserAdapter().openDownload(this.br, downloadLink, dllink, true, 0);
        if (!dl.getConnection().isContentDisposition() && !dl.getConnection().getContentType().startsWith("video")) {
            br.followConnection();
            if (br.containsHTML("No htmlCode read")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.videogoogle.videotemporaryunavailable", "This video is temporary unavailable!"), 60 * 60 * 1000l);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        if (link.getDownloadURL().matches(embed)) {
            dllink = link.getDownloadURL();
        } else {
            br.getPage(link.getDownloadURL());
            // Check this way because language of site is different for everyone
            if (!br.containsHTML("googleplayer\\.swf")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String videoID = new Regex(link.getDownloadURL(), "((\\-)?\\d+)$").getMatch(0);
            String name = br.getRegex("<title>(.*?)</title>").getMatch(0);
            if (name == null || "301 Moved".equals(name)) {
                name = videoID;
            }
            link.setFinalFileName(Encoding.htmlDecode(name) + ".flv");
            dllink = br.getRegex("videoUrl\\\\x3d(http://.*?\\.googlevideo\\.com/videoplayback.*?)\\\\x26thumbnailUrl").getMatch(0);
            if (dllink == null) {
                br.getPage("http://video.google.com/videofeed?fgvns=1&fai=1&docid=" + videoID);
                dllink = br.getRegex("<media:content url=\"(http://[a-z0-9\\.]+\\.googlevideo\\.com/[^<>\"]*?)\"").getMatch(0);
            }
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dllink = Encoding.htmlDecode(dllink);
            dllink = Encoding.urlDecode(dllink, true);
        }
        /* 2020-07-08: Google doesn't like users using old User-Agents and might return 403 if done so. */
        br.getHeaders().put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36");
        br.setFollowRedirects(true);
        // In case the link redirects to the finallink
        URLConnectionAdapter con = null;
        try {
            con = br.openHeadConnection(Encoding.unicodeDecode(dllink));
            if (!con.getContentType().contains("video")) {
                logger.info("Directurl seems to have expired - trying to refresh it");
                dllink = refreshDirectlink(link);
                if (dllink == null) {
                    /* Plugin broken or offline --> Most likely offline */
                    logger.info("Failed to refresh directurl");
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                logger.info("Successfully refreshed directurl");
                // you must save this refresh! if the user stops download, it will keep trying trying original.. and its expired
                link.setPluginPatternMatcher(dllink);
                con = br.openGetConnection(dllink);
            }
            String fileName = null;
            if (con.getContentType().contains("video")) {
                link.setDownloadSize(con.getLongContentLength());
                if (link.isNameSet()) {
                    // maybe we set a filename but doesn't have extension yet!
                    fileName = link.getName();
                    final String ext = jd.plugins.hoster.DirectHTTP.getExtensionFromMimeType(con.getContentType());
                    if (ext != null && !fileName.contains("." + ext)) {
                        fileName = fileName + "." + ext;
                        link.setName(fileName);
                    }
                }
                if (fileName == null) {
                    final String id = new Regex(con.getRequest().getUrl(), "id=(.*?)($|&)").getMatch(0);
                    final String itag = new Regex(con.getRequest().getUrl(), "itag=(.*?)($|&)").getMatch(0);
                    fileName = HTTPConnectionUtils.getFileNameFromDispositionHeader(con.getHeaderField(HTTPConstants.HEADER_RESPONSE_CONTENT_DISPOSITION));
                    if (fileName == null) {
                        if (id != null && itag != null) {
                            final String ext = jd.plugins.hoster.DirectHTTP.getExtensionFromMimeType(con.getContentType());
                            fileName = "contentongoogle_" + id + "_" + itag + "." + ext;
                        } else {
                            fileName = Plugin.extractFileNameFromURL(con.getRequest().getUrl());
                            final String ext = Files.getExtension(fileName);
                            final ExtensionsFilterInterface compiledExt = CompiledFiletypeFilter.getExtensionsFilterInterface(ext);
                            if (compiledExt == null || !(compiledExt instanceof CompiledFiletypeFilter.VideoExtensions)) {
                                fileName = null;
                            }
                        }
                    }
                    fileName = SimpleFTP.BestEncodingGuessingURLDecode(fileName);
                    if (fileName != null) {
                        if (link.getFinalFileName() == null) {
                            // filenames can be set by other plugins.. ie. decrypters, dont fuck with this.
                            link.setFinalFileName(fileName);
                        } else {
                            link.setName(fileName);
                        }
                    }
                }
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            return AvailableStatus.TRUE;
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
    }

    /**
     * Refresh directurls from external providers
     *
     * @throws Exception
     */
    private String refreshDirectlink(final DownloadLink dl) throws Exception {
        if (dl.getDownloadURL().matches(embed)) {
            final String refresh_url_plugin = dl.getStringProperty("refresh_url_plugin", null);
            if (refresh_url_plugin != null) {
                return ((RefreshSessionLink) JDUtilities.getPluginForDecrypt(refresh_url_plugin)).refreshVideoDirectUrl(dl);
            }
        }
        return null;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }
}