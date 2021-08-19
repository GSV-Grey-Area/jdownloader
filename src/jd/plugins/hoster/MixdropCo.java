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
import java.util.ArrayList;
import java.util.List;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.plugins.components.antiDDoSForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class MixdropCo extends antiDDoSForHost {
    public MixdropCo(PluginWrapper wrapper) {
        super(wrapper);
        /* 2019-09-30: They do not have/sell premium accounts */
        // this.enablePremium("");
    }

    @Override
    public String getAGBLink() {
        return "https://mixdrop.co/terms/";
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "mixdrop.co", "mixdrop.to", "mixdrop.club", "mixdrop.sx" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:f|e)/([a-z0-9]+)");
        }
        return ret.toArray(new String[0]);
    }

    /* Connection stuff */
    private static final boolean FREE_RESUME           = true;
    private static final int     FREE_MAXCHUNKS        = 1;
    private static final int     FREE_MAXDOWNLOADS     = 20;
    /** Documentation: https://mixdrop.co/api */
    private static final String  API_BASE              = "https://api.mixdrop.co";
    private static final boolean USE_API_FOR_LINKCHECK = true;

    // private static final boolean ACCOUNT_FREE_RESUME = true;
    // private static final int ACCOUNT_FREE_MAXCHUNKS = 0;
    // private static final int ACCOUNT_FREE_MAXDOWNLOADS = 20;
    // private static final boolean ACCOUNT_PREMIUM_RESUME = true;
    // private static final int ACCOUNT_PREMIUM_MAXCHUNKS = 0;
    // private static final int ACCOUNT_PREMIUM_MAXDOWNLOADS = 20;
    //
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

    private String getNormalFileURL(final DownloadLink link) {
        return link.getPluginPatternMatcher().replaceFirst("/e/", "/f/").replaceAll("(?i)http://", "https://");
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    private String getAPIMail() {
        return "psp@jdownloader.org";
    }

    private String getAPIKey() {
        return "u3aH2kgUYOQ36hd";
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        final String fid = this.getFID(link);
        String filename = null;
        if (USE_API_FOR_LINKCHECK) {
            /* 2019-09-30: Let's just use it that way and hope it keeps working. */
            /*
             * https://mixdrop.co/api#fileinfo --> Also supports multiple fileIDs but as we are unsure how long this will last and this is
             * only a small filehost, we're only using this to check single fileIDs.
             */
            getPage(API_BASE + "/fileinfo?email=" + Encoding.urlEncode(getAPIMail()) + "&key=" + getAPIKey() + "&ref[]=" + this.getFID(link));
            if (br.getHttpConnection().getResponseCode() == 404 || !"true".equalsIgnoreCase(PluginJSonUtils.getJson(br, "success"))) {
                /* E.g. {"success":false,"result":{"msg":"file not found"}} */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if ("true".equalsIgnoreCase(PluginJSonUtils.getJson(br, "deleted"))) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                filename = PluginJSonUtils.getJson(br, "title");
                if (filename != null) {
                    link.setFinalFileName(filename);
                }
                final String filesize = PluginJSonUtils.getJson(br, "size");
                if (filesize != null) {
                    // size is not verified! can be different!
                    link.setDownloadSize(Long.parseLong(filesize));
                }
            }
        } else {
            getPage(getNormalFileURL(link));
            if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("/imgs/illustration-notfound\\.png")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                final Regex fileinfo = br.getRegex("imgs/icon-file\\.png\"[^>]*/> <span title=\"([^\"]+)\">[^<>]*</span>([^<>\"]+)</div>");
                filename = fileinfo.getMatch(0);
                final String filesize = fileinfo.getMatch(1);
                if (filesize != null) {
                    link.setDownloadSize(SizeFormatter.getSize(filesize));
                }
            }
        }
        if (StringUtils.isEmpty(filename)) {
            /* Fallback */
            filename = fid;
        }
        filename = Encoding.htmlDecode(filename).trim();
        link.setName(filename);
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        doFree(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        String dllink = checkDirectLink(link, directlinkproperty);
        if (dllink == null) {
            if (USE_API_FOR_LINKCHECK) {
                getPage(getNormalFileURL(link));
            }
            br.getHeaders().put("x-requested-with", "XMLHttpRequest");
            /** 2021-03-03: E.g. extra step needed for .mp4 files but not for .zip files (which they call "folders"). */
            final String continueURL = br.getRegex("((?://[^/]+/f/[a-z0-9]+)?\\?download)").getMatch(0);
            if (continueURL != null) {
                logger.info("Found continueURL");
                getPage(continueURL);
            } else {
                logger.info("Failed to find continueURL");
            }
            String csrftoken = br.getRegex("name=\"csrf\" content=\"([^<>\"]+)\"").getMatch(0);
            if (csrftoken == null) {
                csrftoken = "";
            }
            final UrlQuery query = new UrlQuery();
            query.add("a", "genticket");
            query.add("csrf", csrftoken);
            /* 2019-12-13: Invisible reCaptcha */
            final boolean requiresCaptcha = true;
            if (requiresCaptcha) {
                final String recaptchaV2Response = getCaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                query.appendEncoded("token", recaptchaV2Response);
            }
            postPage(br.getURL(), query.toString());
            dllink = PluginJSonUtils.getJson(br, "url");
            if (StringUtils.isEmpty(dllink)) {
                if (br.containsHTML("Failed captcha verification")) {
                    /*
                     * 2020-04-20: Should never happen but happens:
                     * {"type":"error","msg":"Failed captcha verification. Please try again. #errcode: 2"}
                     */
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                } else if (br.containsHTML("File not found")) {
                    /* 2020-06-08: {"type":"error","msg":"File not found"} */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            /* 2019-09-30: Skip short pre-download waittime */
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resumable, maxchunks);
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
        link.setProperty(directlinkproperty, dl.getConnection().getURL().toString());
        dl.startDownload();
    }

    protected CaptchaHelperHostPluginRecaptchaV2 getCaptchaHelperHostPluginRecaptchaV2(PluginForHost plugin, Browser br) throws PluginException {
        return new CaptchaHelperHostPluginRecaptchaV2(this, br, this.getReCaptchaKey()) {
            @Override
            public org.jdownloader.captcha.v2.challenge.recaptcha.v2.AbstractRecaptchaV2.TYPE getType() {
                return TYPE.INVISIBLE;
            }
        };
    }

    public String getReCaptchaKey() {
        /* 2019-12-13 */
        return "6LetXaoUAAAAAB6axgg4WLG9oZ_6QLTsFXZj-5sd";
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        final String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = br2.openHeadConnection(dllink);
                if (!looksLikeDownloadableContent(con)) {
                    throw new IOException();
                } else {
                    return dllink;
                }
            } catch (final Exception e) {
                logger.log(e);
                downloadLink.setProperty(property, Property.NULL);
                return null;
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        } else {
            return null;
        }
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

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}