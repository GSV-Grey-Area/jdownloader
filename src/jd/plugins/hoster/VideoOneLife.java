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

import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.plugins.controller.host.LazyHostPlugin.FEATURE;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "video-one.life" }, urls = { "https?://(?:www\\.)?video\\-one\\.(?:com|life)/.+" })
public class VideoOneLife extends PluginForHost {
    public VideoOneLife(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public FEATURE[] getFeatures() {
        return new FEATURE[] { FEATURE.XXX };
    }
    /* DEV NOTES */
    // Tags: Porn plugin
    // protocol: no https
    // other:

    /* Extension which will be used if no correct extension is found */
    private static final String default_extension = ".mp4";
    /* Connection stuff */
    private static final int    free_maxdownloads = -1;
    private String              dllink            = null;
    private boolean             server_issues     = false;

    @Override
    public String getAGBLink() {
        return "https://video-one.com/";
    }

    @Override
    public String rewriteHost(String host) {
        if (host == null || "video-one.com".equals(host)) {
            return "video-one.life";
        }
        return super.rewriteHost(host);
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), "/([^/]+)/?$").getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        dllink = null;
        server_issues = false;
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        final String fid = getFID(link);
        br.getPage(link.getPluginPatternMatcher());
        if (isOffline(this.br)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = br.getRegex("<meta property=\"og:title\" content=\"([^<>\"]+)\"/>").getMatch(0);
        if (StringUtils.isEmpty(filename)) {
            filename = fid;
        }
        /* Possible http urls in html: <meta property="og:video" content="/hvideo/12345678/1.mp4" /> */
        dllink = br.getRegex("([^/]+\\.video\\-one\\.com/video/[^<>\"]+\\.m3u8)").getMatch(0);
        if (dllink == null) {
            dllink = br.getRegex("(/[^<>\"]+\\.m3u8)").getMatch(0);
        }
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (dllink == null) {
            /* 2020-03-11: Treat possible non-video-content as offline */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        filename = Encoding.htmlDecode(filename);
        filename = filename.trim();
        filename = encodeUnicode(filename);
        final String ext = default_extension;
        if (!filename.endsWith(ext)) {
            filename += ext;
        }
        link.setFinalFileName(filename);
        return AvailableStatus.TRUE;
    }

    public static boolean isOffline(final Browser br) {
        return br.getHttpConnection().getResponseCode() == 404;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (StringUtils.isEmpty(dllink)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (!this.dllink.startsWith("http") && !this.dllink.startsWith("/")) {
            this.dllink = "https://" + this.dllink;
        }
        br.getPage(this.dllink);
        final HlsContainer hlsbest = HlsContainer.findBestVideoByBandwidth(HlsContainer.getHlsQualities(this.br));
        if (hlsbest == null) {
            /* E.g. response 404 when accessing HLS-master */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error - stream broken?");
        }
        this.dllink = hlsbest.getDownloadurl();
        checkFFmpeg(link, "Download a HLS Stream");
        dl = new HLSDownloader(link, br, this.dllink);
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
