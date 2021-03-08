//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
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

import jd.PluginWrapper;
import jd.nutils.encoding.Encoding;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "yunpan.cn" }, urls = { "http://yunpandecrypted\\.cn/\\d+" })
public class YunPanCn extends PluginForHost {
    public static final String html_preDownloadPassword = "class=\"pwd-input\"";

    public YunPanCn(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://yunpan.360.cn/resource/html/agreement.html";
    }

    private String folderid = null;
    private String fileid   = null;
    private String host     = null;
    private String mainlink = null;

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        mainlink = link.getStringProperty("mainlink", null);
        folderid = link.getStringProperty("folderid", null);
        fileid = link.getStringProperty("fileid", null);
        host = link.getStringProperty("host", null);
        if (folderid == null || fileid == null || mainlink == null || host == null) {
            /* Required data is missing --> Should never happen */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        br.getPage(mainlink);
        if (this.br.getHttpConnection().getResponseCode() == 404 || this.br.containsHTML("id=\"linkError\"")) {
            // if the link was removed, it wouldn't have a password!
            link.getLinkStatus().setStatusText("This file requires pre-download password!");
            return AvailableStatus.TRUE;
        }
        return AvailableStatus.TRUE;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        if (br.containsHTML(html_preDownloadPassword)) {
            boolean failed = true;
            for (int i = 0; i != 3; i++) {
                String passCode = link.getDownloadPassword();
                if (passCode == null) {
                    passCode = getUserInput("Password?", link);
                }
                if (passCode == null) {
                    logger.info("User has entered blank password, exiting handlePassword");
                    link.setDownloadPassword(null);
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
                }
                br.postPage("http://" + host + "/share/verifyPassword", "shorturl=" + folderid + "&linkpassword=" + Encoding.urlEncode(passCode));
                if (br.containsHTML("\"errno\":0,")) {
                    link.setDownloadPassword(passCode);
                    failed = false;
                    break;
                } else {
                    link.setDownloadPassword(null);
                }
            }
            if (failed) {
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
            }
            br.getPage(mainlink);
        }
        final String download_permit_token = this.br.getRegex("download_permit_token[\t\n\r ]*?:[\t\n\r ]*?\\'([^<>\"\\']+)\\'").getMatch(0);
        br.getHeaders().put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        String postdata = "shorturl=" + folderid + "&nid=" + fileid;
        if (download_permit_token != null) {
            postdata += "&download_permit_token=" + Encoding.urlEncode(download_permit_token);
        }
        br.postPage("https://" + host + "/share/downloadfile/", postdata);
        String dllink = br.getRegex("\"downloadurl\":\"(https?[^<>\"]*?)\"").getMatch(0);
        if (dllink == null) {
            final String errmsg = PluginJSonUtils.getJson(br, "errmsg");
            if (download_permit_token == null) {
                throw new PluginException(LinkStatus.ERROR_FATAL, "Download only possible with the yunpan.cn software");
            } else if (errmsg != null) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errmsg);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dllink = dllink.replace("\\", "");
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }
}