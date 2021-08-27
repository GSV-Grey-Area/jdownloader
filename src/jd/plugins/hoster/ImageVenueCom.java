//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
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
import java.net.MalformedURLException;

import org.appwork.utils.StringUtils;
import org.appwork.utils.parser.UrlQuery;

import jd.PluginWrapper;
import jd.http.URLConnectionAdapter;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "imagevenue.com" }, urls = { "https?://(?:www\\.)?img[0-9]+\\.imagevenue\\.com/img\\.php\\?(loc=[^&]+\\&)?image=.{4,300}|https?://(?:www\\.)?imagevenue\\.com/view/o/\\?i=[^\\&]+\\&h=[^\\&]+" })
public class ImageVenueCom extends PluginForHost {
    public ImageVenueCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://imagevenue.com/tos.php";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    private String getFilenameURL(final DownloadLink link) throws MalformedURLException {
        final UrlQuery query = new UrlQuery().parse(link.getPluginPatternMatcher());
        String name = query.get("image");
        if (name == null) {
            name = query.get("i");
        }
        return name;
    }

    private String dllink = null;

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        // Offline links should also have nice filenames
        link.setName(getFilenameURL(link));
        this.br.setAllowedResponseCodes(500);
        br.setFollowRedirects(true);
        br.getPage(link.getDownloadURL());
        /* Error handling */
        if (br.containsHTML("This image does not exist on this server|<title>404 Not Found</title>|>The requested URL /img\\.php was not found on this server\\.<") || this.br.getHttpConnection().getResponseCode() == 404 || this.br.getHttpConnection().getResponseCode() == 500) {
            logger.warning("File offline");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = null;
        dllink = br.getRegex("id=(?:\"|\\')thepic(?:\"|\\')[^>]*?.*?SRC=(?:\"|\\')(.*?)(?:\"|\\')").getMatch(0);
        if (dllink == null && (br.containsHTML("Continue to your image") || br.containsHTML("Continue to ImageVenue"))) {
            br.getPage(link.getDownloadURL());
            dllink = br.getRegex("id=(?:\"|\\')thepic(?:\"|\\')[^>]*?.*?SRC=(?:\"|\\')(.*?)(?:\"|\\')").getMatch(0);
        }
        if (dllink == null) {
            /* 2020-06-22 */
            dllink = br.getRegex("data-toggle=\"full\">\\s*<img src=\"(https?://[^<>\"]+)\"").getMatch(0);
        } else {
            if (dllink == null) {
                if (br.containsHTML("tempval\\.focus\\(\\)")) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                logger.warning("Could not find finallink reference");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String server = new Regex(link.getDownloadURL(), "(img[0-9]+\\.imagevenue\\.com/)").getMatch(0);
            dllink = "http://" + server + dllink;
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        String ending = new Regex(dllink, "imagevenue\\.com.*?\\.(.{3,4}$)").getMatch(0);
        String filename0 = new Regex(dllink, "imagevenue\\.com/.*?/.*?/\\d+.*?_(.*?)($|\\..{2,4}$)").getMatch(0);
        if (ending != null && filename0 != null) {
            filename = filename0 + "." + ending;
        }
        if (!StringUtils.isEmpty(filename)) {
            link.setName(filename.trim());
        }
        URLConnectionAdapter con = null;
        try {
            con = br.openHeadConnection(dllink);
            if (!this.looksLikeDownloadableContent(con)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (con.getCompleteContentLength() == 14396) {
                /* 2021-08-27: Special "404 not image unavailable" dummy picture. */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                final long size = con.getCompleteContentLength();
                if (size > 0) {
                    link.setDownloadSize(size);
                }
            }
        } finally {
            try {
                if (con != null) {
                    con.disconnect();
                }
            } catch (final Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        if (StringUtils.isEmpty(dllink)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 1);
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

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}