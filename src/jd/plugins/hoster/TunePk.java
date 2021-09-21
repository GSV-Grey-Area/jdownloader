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
package jd.plugins.hoster;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.appwork.utils.StringUtils;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Browser.BrowserException;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "tune.pk" }, urls = { "https?://(?:www\\.)?tune\\.pk/player/embed_player\\.php\\?vid=\\d+|https?://embed\\.tune\\.pk/play/\\d+|https?(?:www\\.)?://tune\\.pk/video/\\d+" })
public class TunePk extends PluginForHost {
    public TunePk(PluginWrapper wrapper) {
        super(wrapper);
    }

    /* DEV NOTES */
    // Tags:
    // protocol: no https
    // other:
    /* Extension which will be used if no correct extension is found */
    private static final String  default_Extension = ".mp4";
    /* Connection stuff */
    private static final boolean free_resume       = true;
    private static final int     free_maxchunks    = 0;
    private static final int     free_maxdownloads = -1;
    private String               dllink            = null;
    private boolean              server_issues     = false;
    BrowserException             e                 = null;

    @Override
    public String getAGBLink() {
        return "http://tune.pk/policy/terms";
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
        return new Regex(link.getPluginPatternMatcher(), "(\\d+)$").getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        if (!link.isNameSet()) {
            link.setName(this.getFID(link) + ".mp4");
        }
        dllink = null;
        server_issues = false;
        e = null;
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.setAllowedResponseCodes(400);
        final String fid = getFID(link);
        // br.getPage("https://embed." + this.getHost() + "/play/" + fid + "?autoplay=no&ssl=no&inline=true");
        // br.getPage(link.getDownloadURL().replace("http:", "https:"));
        /* 2020-05-13: Static key from website */
        br.getHeaders().put("x-key", "777750fea4d3bd585bf47dc1873619fc");
        br.setAllowedResponseCodes(400);
        br.getPage("https://" + this.getHost() + "/api/v3/videos/" + fid + "/player?snippets=configs,structure&ref=undefined");
        if (br.getHttpConnection().getResponseCode() == 400 || br.getHttpConnection().getResponseCode() == 404) {
            /* E.g. Woops,<br>this video has been deactivated <a href="//tune.pk" class="gotune" target="_blank">Goto tune.pk</a> */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (br.containsHTML("Unable to load player configurations")) {
            /* Old fallback to website */
            br.getPage("https://embed." + getHost() + "/play/" + fid + "?autoplay=no&ssl=yes&inline=true");
            if (br.containsHTML(">this video has been deactivated")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            dllink = br.getRegex("contentURL\" content=\"([^<>\"]+)\"").getMatch(0);
            checkSize(link, dllink);
            String filename = getTitleFromEmbedWebsite();
            filename = Encoding.htmlDecode(filename);
            filename = filename.trim();
            filename = encodeUnicode(filename);
            String ext = getFileNameExtensionFromString(dllink, default_Extension);
            if (dllink != null && ext == null) {
                ext = getFileNameExtensionFromString(dllink, default_Extension);
                if (StringUtils.isEmpty(ext)) {
                    ext = default_Extension;
                }
            }
            link.setFinalFileName(filename);
            return AvailableStatus.TRUE;
        }
        Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
        final String errormessage = (String) JavaScriptEngineFactory.walkJson(entries, "data/configs/error/message");
        if (!StringUtils.isEmpty(errormessage)) {
            /* E.g. "This video has been deactivated" or "This video is dead!!" */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "data/configs/details");
        String filename = (String) JavaScriptEngineFactory.walkJson(entries, "video/title");
        /* Find highest quality */
        final List<Object> ressourcelist = (List<Object>) JavaScriptEngineFactory.walkJson(entries, "player/sources");
        String dllinktemp = null;
        long bitratetemp = 0;
        long bitratemax = 0;
        for (final Object qualityo : ressourcelist) {
            entries = (Map<String, Object>) qualityo;
            dllinktemp = (String) entries.get("file");
            bitratetemp = JavaScriptEngineFactory.toLong(entries.get("bitrate"), 0);
            if (StringUtils.isEmpty(dllinktemp) || bitratetemp <= 0) {
                /* Skip invalid objects */
                continue;
            }
            final boolean isHLS = dllinktemp.contains(".m3u8");
            if (isHLS && !dllinktemp.contains("/index.m3u8")) {
                /* 2020-05-13: We have to get around the index files */
                logger.info("Skipping quality because it does not work: " + dllinktemp);
                continue;
            } else if (bitratetemp > bitratemax && !StringUtils.isEmpty(dllinktemp)) {
                if (isHLS) {
                    dllinktemp = dllinktemp.replace("/index.m3u8", "/" + bitratetemp + ".m3u8");
                }
                bitratemax = bitratetemp;
                dllink = dllinktemp;
            }
        }
        if (filename != null) {
            filename = Encoding.htmlDecode(filename);
            filename = filename.trim();
            filename = encodeUnicode(filename);
            if (!filename.endsWith(default_Extension)) {
                filename += default_Extension;
            }
            link.setFinalFileName(filename);
        }
        if (dllink != null && !dllink.contains(".m3u8")) {
            br.setFollowRedirects(true);
            URLConnectionAdapter con = null;
            try {
                con = br.openHeadConnection(dllink);
                if (this.e == null) {
                    if (this.looksLikeDownloadableContent(con)) {
                        if (con.getCompleteContentLength() > 0) {
                            link.setVerifiedFileSize(con.getCompleteContentLength());
                        }
                    } else {
                        server_issues = true;
                    }
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        } else {
            /* We cannot be sure whether we have the correct extension or not! */
            link.setName(filename);
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (this.e != null) {
            throw this.e;
        } else if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (dllink.contains(".m3u8")) {
            /* HLS download - new since 2020-05-13 */
            checkFFmpeg(link, "Download a HLS Stream");
            dl = new HLSDownloader(link, br, dllink);
            dl.startDownload();
        } else {
            /* HTTP download */
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, free_resume, free_maxchunks);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                if (dl.getConnection().getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
                } else if (dl.getConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
                }
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                try {
                    dl.getConnection().disconnect();
                } catch (final Throwable e) {
                }
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl.startDownload();
        }
    }

    /** For embed.tune.pk. */
    private String getTitleFromEmbedWebsite() {
        String title = br.getRegex("details\\.video\\.title[\t\n\r ]*?=[\t\n\r ]*?\\'([^<>\"\\']+)\\';").getMatch(0);
        if (title == null) {
            title = br.getRegex("<title>([^<>\"]+) \\| Tune\\.pk</title>").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("itemprop=\"name\">([^<>\"]*?)<").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("<title>([^<>\"]*?)</title>").getMatch(0);
        }
        return title;
    }

    private String checkSize(final DownloadLink link, final String flink) throws Exception {
        URLConnectionAdapter con = null;
        final Browser br2 = br.cloneBrowser();
        br2.setFollowRedirects(true);
        try {
            con = br2.openGetConnection(flink);
            if (this.looksLikeDownloadableContent(con)) {
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
                dllink = flink;
            } else {
                dllink = null;
            }
        } catch (final Exception e) {
        } finally {
            try {
                con.disconnect();
            } catch (final Exception e) {
            }
        }
        return dllink;
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
