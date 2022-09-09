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
import java.text.DecimalFormat;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "myspass.de", "tvtotal.prosieben.de" }, urls = { "https?://(?:www\\.)?myspassdecrypted\\.de/.+\\d+/?$", "https?://tvtotal\\.prosieben\\.de/(videos/.*?/\\d+/|videoplayer/\\?id=\\d+)" })
public class MySpassDe extends PluginForHost {
    public MySpassDe(PluginWrapper wrapper) {
        super(wrapper);
        setConfigElements();
    }

    private String  dllink        = null;
    private boolean server_issues = false;

    @Override
    public String getAGBLink() {
        return "http://www.myspass.de/myspass/kontakt/";
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
        return new Regex(link.getPluginPatternMatcher(), "(\\d+)/?$").getMatch(0);
    }

    /*
     * Example final url (18.05.2015):
     * http://x3583brainc11021.s.o.l.lb.core-cdn.net/secdl/78de6150fffffffffff1f136aff77d61/55593149/11021brainpool/ondemand
     * /3583brainpool/163840/myspass2009/14/660/10680/18471/18471_61.mp4
     */
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        return requestFileInformation(link, false);
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws IOException, PluginException {
        dllink = null;
        server_issues = false;
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        final String fid = new Regex(link.getPluginPatternMatcher(), "(\\d+)/?$").getMatch(0);
        // br.getPage("http://www.myspass.de/myspass/includes/apps/video/getvideometadataxml.php?id=" + fid + "&0." +
        // System.currentTimeMillis());
        /* 2018-12-29: New */
        br.getPage("https://www.myspass.de/includes/apps/video/getvideometadataxml.php?id=" + fid);
        /* Alternative: http://www.myspass.de/myspass/includes/apps/video/getvideometadataxml.php?id=<fid> */
        if (br.containsHTML("<url_flv><\\!\\[CDATA\\[\\]\\]></url_flv>")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* Build our filename */
        /* Links added via decrypter can have this set to FALSE as it is not needed for all filenames e.g. stock car crash challenge. */
        final boolean needs_series_filename = link.getBooleanProperty("needs_series_filename", true);
        final DecimalFormat df = new DecimalFormat("00");
        String filename = getXML("format") + " - ";
        if (needs_series_filename) { // Sometimes episode = 9/Best Of, need regex to get only the integer
            filename += "S" + df.format(Integer.parseInt(getXML("season"))) + "E" + getXML("episode") + " - ";
        }
        filename += getXML("title");
        dllink = getXML("url_flv");
        filename = filename.trim();
        final String ext = ".mp4";
        filename = Encoding.htmlDecode(filename);
        /*
         * 2019-10-30: They've changed their final downloadurls. They modify the one which is present in their XML. However, older Clips
         * which can be accessed via tvtotal.prosieben.de are still easily downloadable.F
         */
        final boolean enable_old_clips_workaround = true;
        if (enable_old_clips_workaround && link.getPluginPatternMatcher().contains("tvtotal.prosieben.de")) {
            logger.info("Attempting workaround for old clips");
            br.getPage(link.getPluginPatternMatcher());
            final String dllink_alt = br.getRegex("videoURL\\s*:\\s*'(http[^<>\"\\']+)'").getMatch(0);
            if (dllink_alt != null) {
                logger.info("Workaround for old clips seems to be successful");
                dllink = dllink_alt;
            } else {
                logger.info("Workaround for old clips failed");
            }
        }
        if (dllink != null) {
            try {
                /* 2020-09-29 */
                logger.info("Trying to 'fix' final downloadurl");
                final Regex dllinkInfo = new Regex(this.dllink, "/myspass2009/\\d+/(\\d+)/(\\d+)/(\\d+)/");
                final int fidInt = Integer.parseInt(this.getFID(link));
                for (int i = 0; i <= 2; i++) {
                    final String tmpStr = dllinkInfo.getMatch(i);
                    final int tmpInt = Integer.parseInt(tmpStr);
                    if (tmpInt > fidInt) {
                        final int newInt = tmpInt / fidInt;
                        this.dllink = dllink.replace(tmpStr, Integer.toString(newInt));
                    }
                }
            } catch (final Throwable e) {
                e.printStackTrace();
                logger.warning("Failed to fix final downloadurl --> Download might not be possible!");
            }
            if (!isDownload) {
                link.setFinalFileName(filename + ext);
                URLConnectionAdapter con = null;
                try {
                    con = br.openHeadConnection(dllink);
                    if (this.looksLikeDownloadableContent(con)) {
                        link.setDownloadSize(con.getCompleteContentLength());
                    } else {
                        server_issues = true;
                    }
                } finally {
                    try {
                        con.disconnect();
                    } catch (Throwable e) {
                    }
                }
            }
        } else {
            link.setName(filename + ext);
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link, true);
        if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /* 2017-02-04: Without the Range Header we'll be limited to ~100 KB/s */
        link.setProperty("ServerComaptibleForByteRangeRequest", true);
        br.getHeaders().put(OPEN_RANGE_REQUEST);
        /* Workaround for old downloadcore bug that can lead to incomplete files */
        br.getHeaders().put("Accept-Encoding", "identity");
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, false, 1);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    private String getXML(final String parameter) {
        return br.getRegex("<" + parameter + "><\\!\\[CDATA\\[(.*?)\\]\\]></" + parameter + ">").getMatch(0);
    }

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), "FAST_LINKCHECK", "Enable fast linkcheck?\r\nFilesize will only be visible on downloadstart!").setDefaultValue(true));
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
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
