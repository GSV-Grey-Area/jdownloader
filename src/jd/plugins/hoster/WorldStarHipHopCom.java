//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
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
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "worldstarhiphop.com" }, urls = { "https?://(?:www\\.)?(?:worldstarhiphop|worldstar)\\.com.*/video(\\d+)?.php\\?v=([a-zA-Z0-9]+)" })
public class WorldStarHipHopCom extends PluginForHost {
    private String dllink = null;

    public WorldStarHipHopCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.worldstarhiphop.com/videos/termsofuse.php";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
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
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(1);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        if (!link.isNameSet()) {
            link.setName(this.getFID(link) + ".mp4");
        }
        br.setCookie("http://worldstaruncut.com/", "worldstarAdultOk", "true");
        br.setFollowRedirects(true);
        br.getPage(link.getDownloadURL());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = null;
        if (br.getURL().contains("worldstaruncut.com/")) {
            if (br.getURL().equals("http://www.worldstarhiphop.com/videos/")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            getURLUniversal();
            filename = br.getRegex("<title>([^<>]*?) \\- World Star Uncut</title>").getMatch(0);
            if (filename == null) {
                filename = br.getRegex("<td width=\"607\" class=\"style4\">([^<>\"]*?) \\(\\*Warning\\*").getMatch(0);
            }
        } else if (br.getURL().contains("worldstarcandy.com/")) {
            filename = br.getRegex("color:#023a70; font\\-size:28px;\">([^<>\"]*?)</span>").getMatch(0);
            getURLUniversal();
        } else {
            if (br.containsHTML("<title>Video: No Video </title>") || !this.canHandle(br.getURL())) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (br.containsHTML(">This video isn\\'t here right now\\.\\.\\.")) {
                link.setName(new Regex(link.getDownloadURL(), "([a-zA-Z0-9]+)$").getMatch(0));
                link.getLinkStatus().setStatusText("Video temporarily unavailable");
                return AvailableStatus.TRUE;
            }
            filename = br.getRegex("\"content-heading\">\\s*<h1>(.*?)</h1>").getMatch(0);
            if (filename == null) {
                filename = br.getRegex("<title>(.*?)( | Video )?</title>").getMatch(0);
            }
            if (filename == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            if (br.containsHTML("videoplayer\\.vevo\\.com/embed/embedded\"")) {
                filename = Encoding.htmlDecode(filename.trim());
                link.getLinkStatus().setStatusText("This video is blocked in your country");
                link.setName(filename + ".mp4");
                return AvailableStatus.TRUE;
            }
            dllink = br.getRegex("v=playFLV\\.php\\?loc=(https?://.*?\\.(mp4|flv))\\&amp;").getMatch(0);
            if (dllink == null) {
                dllink = br.getRegex("(https?://hwcdn\\.net/[a-z0-9]+/cds/\\d+/\\d+/\\d+/.*?\\.(mp4|flv))").getMatch(0);
                if (dllink == null) {
                    dllink = br.getRegex("v=(https?://.*?\\.com/.*?/vid/.*?\\.(mp4|flv))").getMatch(0);
                    if (dllink == null) {
                        getURLUniversal();
                    }
                }
            }
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        filename = Encoding.htmlDecode(filename.trim());
        filename = encodeUnicode(filename);
        String ext = ".mp4";
        if (!dllink.endsWith(".mp4")) {
            ext = ".flv";
        }
        link.setFinalFileName(filename + ext);
        br.getHeaders().put("Referer", "http://hw-static.worldstarhiphop.com/videos/wplayer/NAPP3e.swf");
        dllink = dllink.replace(" ", "");
        Browser br2 = br.cloneBrowser();
        // In case the link redirects to the finallink
        br2.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br2.openGetConnection(dllink);
            if (this.looksLikeDownloadableContent(con)) {
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        } finally {
            try {
                con.disconnect();
            } catch (final Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (br.containsHTML("videoplayer\\.vevo\\.com/embed/embedded\"")) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "This video is blocked in your country");
        }
        if (br.getURL().contains("worldstarhiphop.com/")) {
            if (br.containsHTML(">This video isn\\'t here right now\\.\\.\\.")) {
                link.getLinkStatus().setStatusText("Video temporarily unavailable");
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Video temporarily unavailable", 30 * 60 * 1000l);
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    private void getURLUniversal() {
        dllink = br.getRegex("addVariable\\(\"file\",\"(https?://.*?)\"").getMatch(0);
        if (dllink == null) {
            dllink = br.getRegex("\"(https?://hw\\-videos\\.worldstarhiphop\\.com/u/vid/.*?)\"").getMatch(0);
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {
    }
}