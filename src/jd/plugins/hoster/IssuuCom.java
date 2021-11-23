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
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.jdownloader.plugins.components.config.IssuuComConfig;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "issuu.com" }, urls = { "https?://issuu\\.com/([a-z0-9\\-_\\.]+)/docs/([a-z0-9\\-_]+)" })
public class IssuuCom extends PluginForHost {
    public IssuuCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://issuu.com/signup");
    }

    @Override
    public String getAGBLink() {
        return "https://issuu.com/acceptterms";
    }

    private String             DOCUMENTID           = null;
    public static final String PROPERTY_FINAL_NAME  = "finalname";
    public static final String PROPERTY_DOCUMENT_ID = "document_id";

    /** Using oembed API: http://developers.issuu.com/api/oembed.html */
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        /* Tyically this oembed API returns 501 for offline content */
        this.br.setAllowedResponseCodes(501);
        this.br.setFollowRedirects(true);
        final String filename = link.getStringProperty("finalname");
        if (filename != null) {
            link.setFinalFileName(filename);
        }
        this.br.getPage("https://issuu.com/oembed?format=json&url=" + Encoding.urlEncode(link.getPluginPatternMatcher()));
        if (this.br.getHttpConnection().getResponseCode() != 200) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (filename == null) {
            final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            link.setFinalFileName(entries.get("title") + ".pdf");
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        /* Account required to download */
        throw new AccountRequiredException();
    }

    private void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    this.br.setCookies(this.getHost(), cookies);
                    if (!force) {
                        br.getPage("https://" + this.getHost() + "/");
                        if (isLoggedIn(br)) {
                            logger.info("Cookie login successful");
                            return;
                        } else {
                            logger.info("Cookie login failed");
                            br.clearCookies(br.getHost());
                        }
                    }
                }
                final String lang = System.getProperty("user.language");
                if (isMailAdress(account.getUser())) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nBitte den Benutzername und nicht die Mailadresse in das 'Benutzername' Feld eingeben!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInstead of using your mailadress, please enter your username in the 'username' field!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                logger.info("Performing full login");
                br.setFollowRedirects(false);
                br.getHeaders().put("Accept", "*/*");
                // br.getPage("https://api.issuu.com/query?username=" + Encoding.urlEncode(account.getUser()) + "&password=" +
                // Encoding.urlEncode(account.getPass()) +
                // "&permission=f&loginExpiration=standard&action=issuu.user.login&format=json&jsonCallback=_jqjsp&_" +
                // System.currentTimeMillis() + "=");
                this.br.getPage("https://" + this.getHost() + "/signin?onLogin=%2F");
                String postdata = "username=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()) + "&permission=f&loginExpiration=standard&action=issuu.user.login&format=json";
                final String csrf = this.br.getCookie(this.getHost(), "issuu.model.lcsrf");
                if (csrf != null) {
                    postdata += "&loginCsrf=" + Encoding.urlEncode(csrf);
                }
                br.postPage("https://" + this.br.getHost() + "/query", postdata);
                if (br.getCookie(this.getHost(), "site.model.token", Cookies.NOTDELETEDPATTERN) == null || br.containsHTML("\"message\":\"Login failed\"")) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    private boolean isLoggedIn(final Browser br) {
        if (br.containsHTML("data-track=\"logout\"")) {
            return true;
        } else {
            return false;
        }
    }

    private static boolean isMailAdress(final String value) {
        return value.matches(".+@.+");
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        login(account, true);
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        ai.setStatus("Registered (free) User");
        return ai;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        DOCUMENTID = this.br.getRegex("\"thumbnail_url\":\"https?://image\\.issuu\\.com/([^<>\"/]*?)/").getMatch(0);
        if (DOCUMENTID == null) {
            this.br.getPage(link.getDownloadURL());
            if (br.containsHTML(">We can\\'t find what you\\'re looking for") || this.br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            DOCUMENTID = PluginJSonUtils.getJsonValue(br, "documentId");
        }
        if (DOCUMENTID == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        login(account, true);
        final String token = br.getCookie(this.getHost(), "site.model.token", Cookies.NOTDELETEDPATTERN);
        br.getPage("http://api." + this.getHost() + "/query?documentId=" + this.DOCUMENTID + "&username=" + Encoding.urlEncode(account.getUser()) + "&token=" + Encoding.urlEncode(token) + "&action=issuu.document.download&format=json&jsonCallback=_jqjsp&_" + System.currentTimeMillis() + "=");
        final String code = PluginJSonUtils.getJsonValue(br, "code");
        final String message = PluginJSonUtils.getJsonValue(br, "message");
        if ("015".equals(code) || "Download limit reached".equals(message)) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "Downloadlimit reached", PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
        }
        if ("Document access denied".equals(message)) {
            /* TODO: Find errorcode for this */
            throw new PluginException(LinkStatus.ERROR_FATAL, "This document is not downloadable");
        }
        String dllink = br.getRegex("\"url\":\"(https?://[^<>\"]*?)\"").getMatch(0);
        if (dllink == null) {
            logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dllink = dllink.replace("\\", "");
        // We HAVE to wait here, otherwise we'll get an empty file
        sleep(3 * 1000l, link);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, Encoding.htmlDecode(dllink), true, 0);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            logger.warning("The final dllink seems not to be a file!");
            try {
                br.followConnection(true);
            } catch (IOException e) {
                logger.log(e);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public Class<? extends IssuuComConfig> getConfigInterface() {
        return IssuuComConfig.class;
    }
}