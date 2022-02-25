//jDownloader - Downloadmanager
//Copyright (C) 2017  JD-Team support@jdownloader.org
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
package jd.plugins.hoster;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.plugins.components.antiDDoSForHost;
import org.jdownloader.scripting.JavaScriptEngineFactory;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "yiffyfur.tube" }, urls = { "https?://(?:www\\.)?yiffyfur\\.tube/video/(\\d+)/([A-Za-z0-9\\-]+)" })
public class YiffyfurTube extends antiDDoSForHost {
    public YiffyfurTube(PluginWrapper wrapper) {
        super(wrapper);
    }

    /* DEV NOTES */
    // Tags: Porn plugin
    // other:
    /* Extension which will be used if no correct extension is found */
    private static final String  default_extension = ".mp4";
    /* Connection stuff */
    private static final boolean free_resume       = true;
    private static final int     free_maxchunks    = -2;
    private static final int     free_maxdownloads = -1;
    private String               dllink            = null;
    private boolean              server_issues     = false;

    @Override
    public String getAGBLink() {
        return "https://www.yiffyfur.tube/";
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String linkid = getFID(link);
        if (linkid != null) {
            return this.getHost() + "://" + linkid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, false);
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws Exception {
        link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        dllink = null;
        server_issues = false;
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        List<Object> ressourcelist = null;
        String filename = null;
        String dllink_fallback = null;
        final String streamtype_fallback = "iphone";
        String streamtype = "h264";
        getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String jssource = br.getRegex("videoFilesJson(?:\")?\\s*(?::|=)\\s*(\\[.*?\\])").getMatch(0);
        if (jssource != null) {
            ressourcelist = (List<Object>) JavaScriptEngineFactory.jsonToJavaObject(jssource);
        }
        final String url_filename = new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(1);
        if (StringUtils.isEmpty(filename)) {
            /* Fallback */
            filename = url_filename.replace("-", " ");
        }
        /* RegExes sometimes used for streaming */
        if (ressourcelist != null) {
            try {
                Map<String, Object> entries = null;
                Object quality_temp_o = null;
                long quality_temp = 0;
                String quality_temp_str = null;
                long quality_best = 0;
                String dllink_temp = null;
                for (final Object videoo : ressourcelist) {
                    entries = (Map<String, Object>) videoo;
                    dllink_temp = (String) entries.get("file");
                    quality_temp_o = entries.get("label");
                    if (quality_temp_o != null && quality_temp_o instanceof Long) {
                        quality_temp = JavaScriptEngineFactory.toLong(quality_temp_o, 0);
                    } else if (quality_temp_o != null && quality_temp_o instanceof String) {
                        quality_temp_str = (String) quality_temp_o;
                        if (quality_temp_str.matches("\\d+p.*?")) {
                            /* E.g. '360p' */
                            quality_temp = Long.parseLong(new Regex(quality_temp_str, "(\\d+)p").getMatch(0));
                        } else {
                            /* Bad / Unsupported format --> Try to use height as fallback */
                            quality_temp = JavaScriptEngineFactory.toLong(entries.get("height"), 1);
                        }
                    }
                    if (StringUtils.isEmpty(dllink_temp) || quality_temp == 0) {
                        continue;
                    } else if (dllink_temp.contains(".m3u8")) {
                        /* Skip hls */
                        continue;
                    }
                    if (quality_temp > quality_best) {
                        quality_best = quality_temp;
                        dllink = dllink_temp;
                    }
                }
                if (!StringUtils.isEmpty(dllink)) {
                    logger.info("BEST handling for multiple video source succeeded");
                }
            } catch (final Throwable e) {
                logger.info("BEST handling for multiple video source failed");
            }
        }
        if (StringUtils.isEmpty(dllink) && dllink_fallback != null) {
            dllink = dllink_fallback;
            streamtype = streamtype_fallback;
        }
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        filename = Encoding.htmlDecode(filename);
        filename = filename.trim();
        filename = encodeUnicode(filename);
        String ext;
        if (!StringUtils.isEmpty(dllink)) {
            ext = getFileNameExtensionFromString(dllink, default_extension);
            if (ext != null && !ext.matches("\\.(?:flv|mp4)")) {
                ext = default_extension;
            }
        } else {
            ext = default_extension;
        }
        if (!filename.endsWith(ext)) {
            filename += ext;
        }
        link.setFinalFileName(filename);
        if (StringUtils.isNotEmpty(dllink)) {
            /* Now try to find correct host and base (2020-03-03: hardcoded ) */
            br.getPage(link.getPluginPatternMatcher());
            final String host = br.getRegex("h264Base\\s*=\\s*\\'(https?://[^<>\"\\']+)\\'\\s*\\+ h264Base;").getMatch(0);
            String base = br.getRegex("var h264Base\\s*=\\s*\\'([^<>\"\\']+)\\';").getMatch(0);
            /* 2020-03-03: Hardcode base! */
            base = "/videos/" + streamtype + "/";
            if (host != null && base != null) {
                dllink = host + base + dllink;
            } else if (!dllink.startsWith("http")) {
                dllink = String.format("https://cdn.beastialitytube.link/static/webseed/%s/%s", streamtype, dllink);
            }
        } else {
            /* Final fallback */
            dllink = br.getRegex("\\s+var iphoneVideoSource = \\'(http[^<>\"\\']+\\.mp4)\\';").getMatch(0);
        }
        if (StringUtils.isEmpty(this.dllink)) {
            /* 2021-01-18 */
            this.dllink = "https://www.yiffyfur.tube/static/webseed/" + this.getFID(link) + ".mp4";
        }
        if (!StringUtils.isEmpty(dllink) && !isDownload) {
            /* Do not check URL if user wants to download otherwise we'll get an error 403. */
            URLConnectionAdapter con = null;
            try {
                final Browser brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                con = openAntiDDoSRequestConnection(brc, brc.createHeadRequest(dllink));
                if (!looksLikeDownloadableContent(con)) {
                    server_issues = true;
                } else if (con.getCompleteContentLength() > 0) {
                    link.setDownloadSize(con.getCompleteContentLength());
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link, true);
        if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (StringUtils.isEmpty(dllink)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, free_resume, free_maxchunks);
        if (!looksLikeDownloadableContent(dl.getConnection())) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return free_maxdownloads;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
