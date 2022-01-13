//    jDownloader - Downloadmanager
//    Copyright (C) 2014  JD-Team support@jdownloader.org
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

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
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
import jd.plugins.components.UserAgents;
import jd.plugins.components.UserAgents.BrowserName;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "filestore.to" }, urls = { "https?://(?:www\\.)?filestore\\.to/\\?d=([A-Z0-9]+)" })
public class FilestoreTo extends PluginForHost {
    private String aBrowser = "";

    public FilestoreTo(final PluginWrapper wrapper) {
        super(wrapper);
        setStartIntervall(10000l);
        enablePremium("https://filestore.to/premium");
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String linkid = getFID(link);
        if (linkid != null) {
            return this.getHost() + "://" + linkid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        login(account, true);
        if (!StringUtils.endsWithCaseInsensitive(br.getURL(), "/konto")) {
            br.getPage("/konto");
        }
        final String validUntilString = br.getRegex("Premium-Status\\s*</small>\\s*<div class=\"value text-success\">\\s*(.*?)\\s*Uhr").getMatch(0);
        if (validUntilString != null) {
            final long until = TimeFormatter.getMilliSeconds(validUntilString, "dd'.'MM'.'yyyy' - 'HH':'mm", Locale.ENGLISH);
            ai.setValidUntil(until);
            if (!ai.isExpired()) {
                account.setType(AccountType.PREMIUM);
                account.setMaxSimultanDownloads(20);
                account.setConcurrentUsePossible(true);
                return ai;
            }
        }
        account.setType(AccountType.FREE);
        account.setMaxSimultanDownloads(2);
        account.setConcurrentUsePossible(false);
        return ai;
    }

    private boolean isLoggedinHTML() {
        return br.containsHTML("\"[^\"]*logout\"");
    }

    private boolean login(final Account account, final boolean validateCookies) throws Exception {
        synchronized (account) {
            final Cookies cookies = account.loadCookies("");
            try {
                if (cookies != null) {
                    br.setCookies(getHost(), cookies);
                    if (!validateCookies) {
                        logger.info("Trust cookies without login");
                        return false;
                    }
                    br.getPage("http://" + this.getHost() + "/konto");
                    if (this.isLoggedinHTML()) {
                        logger.info("Cookie login successful");
                        /* refresh saved cookies timestamp */
                        account.saveCookies(br.getCookies(getHost()), "");
                        return true;
                    } else {
                        logger.info("Cookie login failed");
                    }
                }
                logger.info("Performing full login");
                account.clearCookies("");
                br.getPage("http://" + this.getHost() + "/login");
                final Form form = br.getFormbyKey("Email");
                form.put("EMail", Encoding.urlEncode(account.getUser()));
                form.put("Password", Encoding.urlEncode(account.getPass()));
                br.submitForm(form);
                br.followRedirect();
                if (!this.isLoggedinHTML()) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(br.getCookies(getHost()), "");
                return true;
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        login(account, false);
        br.getPage(link.getPluginPatternMatcher());
        if (AccountType.FREE.equals(account.getType())) {
            download(link, account, true, 1);
        } else {
            download(link, account, true, 0);
        }
    }

    @Override
    public String getAGBLink() {
        return "http://www.filestore.to/?p=terms";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public int getTimegapBetweenConnections() {
        return 2000;
    }

    private static AtomicReference<String> agent = new AtomicReference<String>(null);

    private Browser prepBrowser(final Browser prepBr) {
        if (agent.get() == null) {
            agent.set(UserAgents.stringUserAgent(BrowserName.Chrome));
        }
        prepBr.getHeaders().put("User-Agent", agent.get());
        prepBr.setCustomCharset("utf-8");
        return prepBr;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        setBrowserExclusive();
        prepBrowser(br);
        final String url = link.getDownloadURL();
        String filename = null;
        String filesizeStr = null;
        Exception exception = null;
        for (int i = 1; i < 3; i++) {
            try {
                br.getPage(url);
            } catch (final Exception e) {
                logger.log(e);
                exception = e;
                continue;
            }
            haveFun();
            filename = br.getRegex("class=\"file\">\\s*(.*?)\\s*</").getMatch(0);
            if (filename == null) {
                filename = new Regex(aBrowser, "\\s*(File:|Filename:?|Dateiname:?)\\s*(.*?)\\s*(Dateigr??e|(File)?size|Gr??e):?\\s*(\\d+(,\\d+)? (B|KB|MB|GB))").getMatch(1);
                if (filename == null) {
                    filename = new Regex(aBrowser, "und starte dann den Download\\.\\.\\.\\.\\s*[A-Za-z]+:?\\s*([^<>\"/]*\\.(3gp|7zip|7z|abr|ac3|aiff|aifc|aif|ai|au|avi|bin|bat|bz2|cbr|cbz|ccf|chm|cso|cue|cvd|dta|deb|divx|djvu|dlc|dmg|doc|docx|dot|eps|epub|exe|ff|flv|flac|f4v|gsd|gif|gz|iwd|idx|iso|ipa|ipsw|java|jar|jpg|jpeg|load|m2ts|mws|mv|m4v|m4a|mkv|mp2|mp3|mp4|mobi|mov|movie|mpeg|mpe|mpg|mpq|msi|msu|msp|nfo|npk|oga|ogg|ogv|otrkey|par2|pkg|png|pdf|pptx|ppt|pps|ppz|pot|psd|qt|rmvb|rm|rar|ram|ra|rev|rnd|[r-z]\\d{2}|r\\d+|rpm|run|rsdf|reg|rtf|shnf|sh(?!tml)|ssa|smi|sub|srt|snd|sfv|swf|tar\\.gz|tar\\.bz2|tar\\.xz|tar|tgz|tiff|tif|ts|txt|viv|vivo|vob|webm|wav|wmv|wma|xla|xls|xpi|zeno|zip|z\\d+|_[_a-z]{2}))").getMatch(0);
                }
            }
            filesizeStr = new Regex(aBrowser, "(Dateigr??e|(File)?size|Gr??e):?\\s*(\\d+(,\\d+)? (B|KB|MB|GB))").getMatch(1);
            if (filesizeStr == null) {
                filesizeStr = new Regex(aBrowser, "(\\d+(,\\d+)? (B|KB|MB|GB))").getMatch(0);
            }
            if (filename != null) {
                link.setName(Encoding.htmlDecode(filename.trim()));
            }
            if (filesizeStr != null) {
                link.setDownloadSize(SizeFormatter.getSize(filesizeStr.replaceAll(",", "\\.").trim()));
            }
            /** 2020-02-08: File information can be available for offline files too! */
            if (br.containsHTML("(?i)>\\s*Datei nicht gefunden")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.containsHTML("(?i)>\\s*Datei gesperrt")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.containsHTML("(?i)Entweder wurde die Datei von unseren Servern entfernt oder der Download-Link war")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.containsHTML("(?i)>\\s*Für diese Datei ist eine Take Down-Meldung eingegangen")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            return AvailableStatus.TRUE;
        }
        if (exception != null) {
            throw exception;
        } else {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    private void download(final DownloadLink link, final Account account, final boolean resume, int maxChunks) throws Exception {
        final String errorMsg = br.getRegex("class=\"alert alert-danger page-alert mb-2\">\\s*<strong>([^<>]+)</strong>").getMatch(0);
        if (errorMsg != null) {
            /* Check if we should retry */
            if (errorMsg.matches("Datei noch nicht bereit")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errorMsg, 5 * 60 * 1000l);
            } else {
                /* Unknown error: Display to user but do not retry */
                throw new PluginException(LinkStatus.ERROR_FATAL, errorMsg);
            }
        }
        // form 1
        Form f = br.getFormByRegex("(?i)>Download</button>");
        if (f != null) {
            br.submitForm(f);
        }
        // form 2
        f = br.getFormByRegex("(?i)>Download starten</button>");
        if (f != null) {
            // not enforced
            if (account == null || AccountType.FREE.equals(account.getType())) {
                processWait(br);
            }
            br.submitForm(f);
        }
        final String dllink = getDllink(br);
        if (StringUtils.isEmpty(dllink)) {
            if (br.containsHTML("(?i)>\\s*Leider sind aktuell keine freien Downloadslots für Freeuser verfügbar")) {
                throw new AccountRequiredException("Leider sind aktuell keine freien Downloadslots für Freeuser verfügbar");
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        if (resume == false) {
            maxChunks = 1;
        }
        dl = new jd.plugins.BrowserAdapter().openDownload(br, link, dllink, resume, maxChunks);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            final String location = br.getRegex("top\\.location\\.href\\s*=\\s*\"(.*?)\"").getMatch(0);
            if (location != null) {
                br.setFollowRedirects(true);
                br.getPage(location);
            }
            if (br.containsHTML("Derzeit haben wir Serverprobleme und arbeiten daran\\. Bitte nochmal versuchen\\.")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server issues", 15 * 60 * 1000l);
            } else if (br.containsHTML("Derzeit haben wir leider keinen freien Downloadslots frei\\. Bitte nochmal versuchen\\.")) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, 5 * 60 * 1000l);
            } else if (br.getURL().contains("/error/limit")) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Wait before starting new downloads", 5 * 60 * 1000l);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        download(link, null, true, 1);
    }

    private void processWait(final Browser br) throws PluginException {
        final String waittime = br.getRegex("data-wait=\"(\\d+)\"").getMatch(0);
        int wait = 10;
        if (waittime != null && Integer.parseInt(waittime) < 61) {
            wait = Integer.parseInt(waittime);
        }
        sleep(wait * 1001l, getDownloadLink());
    }

    private String getDllink(final Browser br) {
        String dllink = br.getRegex("<a href=(\"|')([^>]*)\\1>hier</a>").getMatch(1);
        if (dllink == null) {
            dllink = br.getRegex("<iframe class=\"downframe\" src=\"(.*?)\"").getMatch(0);
        }
        return dllink;
    }

    // private Browser prepAjax(Browser prepBr) {
    // prepBr.getHeaders().put("Accept", "*/*");
    // prepBr.getHeaders().put("Accept-Charset", null);
    // prepBr.getHeaders().put("X-Requested-With:", "XMLHttpRequest");
    // return prepBr;
    // }
    public void haveFun() throws Exception {
        aBrowser = br.toString();
        aBrowser = aBrowser.replaceAll("(<(p|div)[^>]+(display:none|top:-\\d+)[^>]+>.*?(<\\s*(/\\2\\s*|\\2\\s*/\\s*)>){2})", "");
        aBrowser = aBrowser.replaceAll("(<(table).*?class=\"hide\".*?<\\s*(/\\2\\s*|\\2\\s*/\\s*)>)", "");
        aBrowser = aBrowser.replaceAll("[\r\n\t]+", " ");
        aBrowser = aBrowser.replaceAll("&nbsp;", " ");
        aBrowser = aBrowser.replaceAll("(<[^>]+>)", " ");
    }

    @Override
    public void init() {
        Browser.setRequestIntervalLimitGlobal(getHost(), 500);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {
    }
}