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

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.SiteType.SiteTemplate;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "tadaigou.com" }, urls = { "https?://(?:www\\.)?tadaigou\\.com/file/([A-Za-z0-9]+)\\.html" })
public class TadaigouCom extends PluginForHost {
    public TadaigouCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.tadaigou.com/";
    }

    /* Connection stuff */
    private static final boolean FREE_RESUME       = true;
    private static final int     FREE_MAXCHUNKS    = 0;
    private static final int     FREE_MAXDOWNLOADS = -1;
    // private static final boolean ACCOUNT_FREE_RESUME = false;
    // private static final int ACCOUNT_FREE_MAXCHUNKS = 1;
    // private static final int ACCOUNT_FREE_MAXDOWNLOADS = 1;
    // private static final boolean ACCOUNT_PREMIUM_RESUME = false;
    // private static final int ACCOUNT_PREMIUM_MAXCHUNKS = 1;
    // private static final int ACCOUNT_PREMIUM_MAXDOWNLOADS = 1;
    // /* don't touch the following! */
    // private static AtomicInteger maxPrem = new AtomicInteger(1);

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
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        if (!link.isNameSet()) {
            link.setName(this.getFID(link));
        }
        this.setBrowserExclusive();
        br.getPage(link.getPluginPatternMatcher());
        if (br.containsHTML("文件已经被删除") || this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String filename = br.getRegex("class=\"down_one_lf_tl\"[^>]*>([^<>\"]+)</span>").getMatch(0);
        String filesize = br.getRegex(">\\s*檔案大小：([^<>\"]+)<").getMatch(0);
        if (filename != null) {
            link.setName(Encoding.htmlDecode(filename.trim()));
        }
        if (filesize != null) {
            filesize = Encoding.htmlDecode(filesize);
            filesize += "b";
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        doFree(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        if (!attemptStoredDownloadurlDownload(link, directlinkproperty, resumable, maxchunks)) {
            this.requestFileInformation(link);
            String internalFileID = br.getRegex("add_ref\\((\\d+)\\);").getMatch(0);
            if (internalFileID == null) {
                br.getPage(br.getURL().replace("/file/", "/down/"));
                internalFileID = br.getRegex("add_ref\\((\\d+)\\);").getMatch(0);
                if (internalFileID == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            final Browser ajax = this.br.cloneBrowser();
            ajax.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            /** 2021-04-26: Waittime and captcha is skippable */
            // br.getPage("/ajax.php?action=load_time&ctime=" + System.currentTimeMillis());
            // final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            // final int waitSeconds = ((Number) entries.get("")).intValue();
            // this.sleep(waitSeconds * 1001l, link);
            // br.getPage("https://" + this.br.getHost() + "/d/" + this.getFID(link) + "/" + Encoding.urlEncode(link.getName()) + ".html");
            // final String code = getCaptchaCode("/imagecode.php?t=" + System.currentTimeMillis(), link);
            // br.postPage("/ajax.php", "action=check_code&code=" + Encoding.urlEncode(code));
            // if (br.toString().equals("false")) {
            // throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            // }
            ajax.postPage("/ajax.php", "action=load_down_addr1&file_id=" + internalFileID);
            String dllink = ajax.getRegex("true\\|<a href=\"([^<>\"]+)").getMatch(0);
            if (dllink == null) {
                dllink = ajax.getRegex("true\\|(http[^<>\"]+)").getMatch(0);
            }
            if (StringUtils.isEmpty(dllink)) {
                logger.warning("Failed to find final downloadurl");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resumable, maxchunks);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                if (dl.getConnection().getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 5 * 60 * 1000l);
                } else if (dl.getConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 5 * 60 * 1000l);
                } else if (dl.getConnection().getResponseCode() == 503) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 503 too many connections", 5 * 60 * 1000l);
                }
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error");
            }
            link.setProperty(directlinkproperty, dl.getConnection().getURL().toString());
        }
        dl.startDownload();
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        if (acc == null) {
            /* no account, yes we can expect captcha */
            return true;
        }
        if (acc.getType() == AccountType.FREE) {
            /* Free accounts can have captchas */
            return true;
        }
        /* Premium accounts do not have captchas */
        return false;
    }

    private boolean attemptStoredDownloadurlDownload(final DownloadLink link, final String directlinkproperty, final boolean resumable, final int maxchunks) throws Exception {
        final String url = link.getStringProperty(directlinkproperty);
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        try {
            final Browser brc = br.cloneBrowser();
            dl = new jd.plugins.BrowserAdapter().openDownload(brc, link, url, resumable, maxchunks);
            if (this.looksLikeDownloadableContent(dl.getConnection())) {
                return true;
            } else {
                brc.followConnection(true);
                throw new IOException();
            }
        } catch (final Throwable e) {
            logger.log(e);
            try {
                dl.getConnection().disconnect();
            } catch (Throwable ignore) {
            }
            return false;
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.PhpDisk;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}