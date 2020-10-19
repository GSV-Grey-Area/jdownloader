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

import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "iwara.tv" }, urls = { "https?://(?:[A-Za-z0-9]+\\.)?iwaradecrypted\\.tv/.+" })
public class IwaraTv extends PluginForHost {
    public IwaraTv(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.iwara.tv/user/register");
    }

    /* DEV NOTES */
    // Porn_plugin
    // Tags:
    // protocol: no https
    // other:
    /* Connection stuff */
    private static final boolean free_resume       = true;
    private static final int     free_maxchunks    = 0;
    private static final int     free_maxdownloads = -1;
    private final String         html_privatevideo = ">This video is only available for users that|>Private video<";
    public static final String   html_loggedin     = "/user/logout";
    private static final String  type_image        = "https?://[^/]+/images/.+";
    private String               dllink            = null;
    private boolean              serverIssue       = false;

    @Override
    public String getAGBLink() {
        return "https://www.iwara.tv/";
    }

    @SuppressWarnings("deprecation")
    public void correctDownloadLink(final DownloadLink link) {
        final String fid = getFID(link.getDownloadURL());
        link.setLinkID(fid);
        link.setUrlDownload(link.getDownloadURL().replace("iwaradecrypted.tv/", "iwara.tv/"));
    }

    @Override
    public String rewriteHost(String host) {
        if ("trollvids.com".equals(getHost())) {
            if (host == null || "trollvids.com".equals(host)) {
                return "iwara.tv";
            }
        }
        return super.rewriteHost(host);
    }

    public static Browser prepBR(final Browser br) {
        br.setFollowRedirects(true);
        br.setCustomCharset("UTF-8");
        br.setCookie("iwara.tv", "show_adult", "1");
        return br;
    }

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        /* Website is hosting video content ONLY! */
        link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        dllink = null;
        serverIssue = false;
        this.setBrowserExclusive();
        prepBR(this.br);
        final String fid = getFID(link.getDownloadURL());
        final Account aa = AccountController.getInstance().getValidAccount(this);
        if (aa != null) {
            /* Login if possible */
            try {
                login(this.br, aa, false);
            } catch (final Throwable e) {
            }
        }
        this.br.getPage(link.getDownloadURL());
        br.followRedirect();
        final String uploadername = this.br.getRegex("class=\"username\">([^<>]+)<").getMatch(0);
        String filename = "";
        if (uploadername != null) {
            filename += uploadername + "_";
        }
        filename += fid + "_";
        final String title = br.getRegex("<h1 class=\"title\">([^<>\"]+)</h1>").getMatch(0);
        if (title != null) {
            filename += title;
        }
        filename = Encoding.htmlDecode(filename);
        filename = filename.trim();
        filename = encodeUnicode(filename);
        if (br.getHttpConnection().getResponseCode() == 404) {
            /* Offline */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (this.br.containsHTML(html_privatevideo)) {
            /* Private video */
            link.setName(filename + ".mp4");
            return AvailableStatus.TRUE;
        }
        boolean usedApi = false;
        boolean isVideo = true;
        if (this.br.getURL().matches(type_image)) {
            /* Picture */
            isVideo = false;
            dllink = this.br.getRegex("\"(https?://(?:[a-z0-9]+\\.)?iwara\\.tv/[^<>]+/large/public/[^<>\"]+)\"").getMatch(0);
            if (dllink == null) {
                dllink = this.br.getRegex("(//[^/]+/sites/default/files/styles/large/public/photos/[^\"]+)").getMatch(0);
                if (dllink != null) {
                    dllink = "https:" + dllink;
                }
            }
        } else if (this.br.containsHTML("name=\"flashvars\"") || this.br.containsHTML("flowplayer\\.org/")) {
            /* Video */
            dllink = br.getRegex("<source src=\"(https?://[^<>\"]+)\" type=\"video/").getMatch(0);
            if (dllink == null) {
                dllink = br.getRegex("\"(https?://(?:www\\.)?iwara\\.tv/sites/default/files/videos/[^<>\"]+)\"").getMatch(0);
            }
        } else if (this.br.containsHTML("jQuery\\.extend")) {
            String drupal = br.getRegex("jQuery\\.extend\\([^{]+(.+)\\);").getMatch(0);
            String videoHash = PluginJSonUtils.getJson(PluginJSonUtils.getJsonNested(drupal, "theme"), "video_hash");
            if (videoHash != null) {
                usedApi = true;
                this.br.getPage("/api/video/" + videoHash);
                if (br.toString().equals("[]")) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Processing video, please check back in a while");
                }
                dllink = PluginJSonUtils.getJson(this.br, "uri");
            }
        } else {
            logger.info("Failed to find downloadable content");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (filename == null || dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        String filenameExt;
        if (!usedApi) {
            dllink = Encoding.htmlDecode(dllink);
            filenameExt = dllink;
        } else {
            filenameExt = filename;
        }
        final String ext = getFileNameExtensionFromString(filenameExt, isVideo ? ".mp4" : ".png");
        if (!filename.endsWith(ext)) {
            filename += ext;
        }
        link.setFinalFileName(filename);
        // In case the link redirects to the finallink
        br.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br.openHeadConnection(dllink);
            if (this.looksLikeDownloadableContent(con)) {
                link.setDownloadSize(con.getCompleteContentLength());
                link.setProperty("directlink", dllink);
            } else {
                serverIssue = true;
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
        doFree(link);
    }

    public void doFree(final DownloadLink link) throws Exception {
        if (this.br.containsHTML(html_privatevideo)) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        } else if (serverIssue) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 2 * 60 * 1000l);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, free_resume, free_maxchunks);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            }
            br.followConnection();
            try {
                dl.getConnection().disconnect();
            } catch (final Throwable e) {
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    /** TODO: Improve this */
    public static String getFID(final String url) {
        String fid = new Regex(url, "/videos/(.+)").getMatch(0);
        if (fid == null) {
            fid = new Regex(url, "/node/(\\d+)").getMatch(0);
        }
        if (fid == null) {
            fid = new Regex(url, "/images/([^/]+)").getMatch(0);
        }
        return fid;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return free_maxdownloads;
    }

    public void login(Browser br, final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                prepBR(br);
                final Cookies cookies = account.loadCookies("");
                boolean loggedIN = false;
                if (cookies != null) {
                    br.setCookies(account.getHoster(), cookies);
                    br.getPage("https://" + account.getHoster());
                    if (br.containsHTML(html_loggedin)) {
                        loggedIN = true;
                    } else {
                        br = prepBR(new Browser());
                    }
                }
                if (!loggedIN) {
                    br.getPage("https://www." + this.getHost() + "/user/login?destination=front");
                    Form loginform = br.getFormbyProperty("id", "user-login");
                    if (loginform == null) {
                        /* Fallback */
                        loginform = br.getForm(0);
                    }
                    if (loginform == null) {
                        logger.warning("Failed to find loginform");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    loginform.put("name", Encoding.urlEncode(account.getUser()));
                    loginform.put("pass", Encoding.urlEncode(account.getPass()));
                    if (loginform.containsHTML("g\\-recaptcha")) {
                        /* 2017-04-28 */
                        final DownloadLink dlinkbefore = this.getDownloadLink();
                        if (dlinkbefore == null) {
                            this.setDownloadLink(new DownloadLink(this, "Account", this.getHost(), "http://" + account.getHoster(), true));
                        }
                        final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                        if (dlinkbefore != null) {
                            this.setDownloadLink(dlinkbefore);
                        }
                        loginform.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                    }
                    /* 2019-12-12: Anti-anti-bot against: https://www.iwara.tv/sites/all/modules/contrib/antibot/js/antibot.js */
                    final String key = PluginJSonUtils.getJson(br, "key");
                    if (key != null) {
                        loginform.put("antibot_key", key);
                    }
                    if (loginform.getAction() == null || loginform.getAction().contains("/antibot")) {
                        loginform.setAction(br.getURL());
                    }
                    br.setCookie(br.getHost(), "has_js", "1");
                    /* Anti-anti-bot END */
                    br.submitForm(loginform);
                    if (!br.containsHTML(html_loggedin)) {
                        if (br.getURL().contains("/antibot")) {
                            /* 2019-12-12: Anti-anti-bot failed :( */
                            logger.warning("Login failed due to anti-bot measures");
                        }
                        if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        } else {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        }
                    }
                }
                account.saveCookies(br.getCookies(account.getHoster()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        try {
            login(this.br, account, true);
        } catch (PluginException e) {
            if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                account.clearCookies("");
            }
            throw e;
        }
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        account.setConcurrentUsePossible(true);
        ai.setStatus("Registered (free) user");
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        /* No need to log in as we are already logged in */
        doFree(link);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
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
