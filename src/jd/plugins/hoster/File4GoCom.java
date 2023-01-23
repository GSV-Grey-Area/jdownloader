//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
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

import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.plugins.components.antiDDoSForHost;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "file4go.net" }, urls = { "https?://(?:www\\.)?file4go\\.(?:net|com)/(?:.*)/([a-zA-Z0-9_=]+)" })
public class File4GoCom extends antiDDoSForHost {
    public File4GoCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(MAINPAGE);
    }

    @Override
    public String getAGBLink() {
        return MAINPAGE;
    }

    private static final String MAINPAGE = "http://www.file4go.net";
    private static Object       LOCK     = new Object();

    @Override
    public String rewriteHost(String host) {
        if (host == null || "file4go.com".equals(host) || "file4go.net".equals(host)) {
            return "file4go.net";
        }
        return super.rewriteHost(host);
    }

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        correctDownloadLink(link);
        br.setCookie(MAINPAGE, "animesonline", "1");
        br.setCookie(MAINPAGE, "musicasab", "1");
        br.setCookie(MAINPAGE, "poup", "1");
        br.setCookie(MAINPAGE, "noadvtday", "0");
        br.setCookie(MAINPAGE, "hellpopab", "1");
        // do not follow redirects, as they can lead to 404 which is faked based on country of origin ?
        getPage(link.getDownloadURL());
        redirectControl(br);
        if (br.containsHTML(">404 ARQUIVO N�O ENCOTRADO<|Arquivo Temporariamente Indisponivel|ARQUIVO DELATADO PELO USUARIO OU REMOVIDO POR <|ARQUIVO DELATADO POR <b>INATIVIDADE|O arquivo Não foi encotrado em nossos servidores")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = br.getRegex(">Nome:</b>\\s*(.*?)\\s*(?:</p>)?</span>").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("<div id=\"titulo_a\">\\s*(.*?)\\s*</div>").getMatch(0);
            if (filename == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        String filesize = br.getRegex(">Tamanho:</b>\\s*(.*?)\\s*</span>").getMatch(0);
        if (filesize == null) {
            filesize = br.getRegex("<b>File size:</b>\\s*([^<>\"]+)\\s*</").getMatch(0);
        }
        if ("".equals(filename) && "0 Bytes".equals(filesize)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        link.setName(filename.trim());
        if (filesize != null) {
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        String directLink = checkDirectLink(link, "directlink");
        if (directLink == null) {
            final Form form = br.getFormByRegex("<input type=\"hidden\" name=\"id\" value=\"(.*?)\">");
            if (form == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            br.submitForm(form);
            submitForm(form);
            directLink = getDllink();
            if (directLink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, directLink, true, -2);
        /* resume no longer supported */
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection();
            if (br.containsHTML("BAIXE SIMULTANEAMENTE COM VELOCIDADE MÁXIMA")) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Too many simultan downloads", 5 * 60 * 1000l);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setProperty("directlink", directLink);
        dl.startDownload();
    }

    private String checkDirectLink(final DownloadLink link, final String property) {
        String dllink = link.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = br2.openHeadConnection(dllink);
                if (this.looksLikeDownloadableContent(con)) {
                    if (con.getCompleteContentLength() > 0) {
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                    }
                    return dllink;
                } else {
                    throw new IOException();
                }
            } catch (final Exception e) {
                link.removeProperty(property);
                logger.log(e);
                return null;
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }
        return null;
    }

    @SuppressWarnings({ "unchecked" })
    private void login(final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                // Load cookies
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof Map<?, ?> && !force) {
                    final Map<String, String> cookies = (Map<String, String>) ret;
                    if (account.isValid()) {
                        for (final Map.Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            if ("FREE".equalsIgnoreCase(key)) {
                                continue;
                            }
                            this.br.setCookie(MAINPAGE, key, value);
                        }
                        return;
                    }
                }
                // once again you can't follow redirects!
                getPage(MAINPAGE);
                redirectControl(br);
                postPage("/login.html", "acao=logar&login=" + Encoding.urlEncode(account.getUser()) + "&senha=" + Encoding.urlEncode(account.getPass()));
                redirectControl(br);
                final String lang = System.getProperty("user.language");
                if (br.getHttpConnection().getResponseCode() == 404) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nServer Fehler 404 - Login momentan nicht möglich!", PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nIServer error 404 - login not possible at the moment!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                if (br.getCookie(MAINPAGE, "FILE4GO") == null) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", fetchCookies(MAINPAGE));
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    /**
     * because this host will randomly redirect to /error.php... Think this is actually because referrer is blank on first request. login
     * can randomly do it also, and be identified as free user, but if you have referrer it doesn't -raztoki20160809
     *
     * @param br
     * @throws Exception
     */
    private void redirectControl(Browser br) throws Exception {
        String redirect = null;
        while ((redirect = br.getRedirectLocation()) != null && !redirect.endsWith("/error.php")) {
            getPage(redirect);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        login(account, true);
        ai.setUnlimitedTraffic();
        String expire = br.getRegex(">Premium Stop: (\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}) </b>").getMatch(0);
        account.setValid(true);
        if (expire == null) {
            final String lang = System.getProperty("user.language");
            if ("de".equalsIgnoreCase(lang)) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nNicht unterstützter Accounttyp!\r\nFalls du denkst diese Meldung sei falsch die Unterstützung dieses Account-Typs sich\r\ndeiner Meinung nach aus irgendeinem Grund lohnt,\r\nkontaktiere uns über das support Forum.", PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUnsupported account type!\r\nIf you think this message is incorrect or it makes sense to add support for this account type\r\ncontact us via our support forum.", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        } else {
            ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "yyyy/MM/dd hh:mm:ss", null));
        }
        ai.setStatus("Premium Account");
        return ai;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        br = new Browser();
        requestFileInformation(link);
        br = new Browser();
        login(account, false);
        br.getPage(link.getDownloadURL());
        final String dllink = getDllink();
        if (dllink == null) {
            logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
        if (dl.getConnection().getContentType().contains("html")) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    private String getDllink() {
        String dllink = br.getRegex("\"(https?://[a-z0-9]+\\.(?:file4go\\.com|sizedrive\\.com)(?::\\d+)?/(?:[^<>\"]+/dll/[^\"]+|beta(?:free)?/[^\"]+))\"").getMatch(0);
        if (dllink == null) {
            dllink = br.getRegex("class\\s*=\\s*\"novobotao download\"\\s*(:?.*?)\\s*href\\s*=\\s*\"(.*?)\">").getMatch(0);
        }
        return dllink;
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
        return 1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    /* NO OVERRIDE!! We need to stay 0.9*compatible */
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        if (acc == null) {
            /* no account, yes we can expect captcha */
            return true;
        }
        if (Boolean.TRUE.equals(acc.getBooleanProperty("free"))) {
            /* free accounts also have captchas */
            return true;
        }
        return false;
    }
}