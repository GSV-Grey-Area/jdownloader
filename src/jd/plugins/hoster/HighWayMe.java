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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.notify.BasicNotify;
import org.jdownloader.gui.notify.BubbleNotify;
import org.jdownloader.gui.notify.BubbleNotify.AbstractNotifyWindowFactory;
import org.jdownloader.gui.notify.gui.AbstractNotifyWindow;
import org.jdownloader.images.AbstractIcon;
import org.jdownloader.plugins.ConditionalSkipReasonException;
import org.jdownloader.plugins.WaitingSkipReason;
import org.jdownloader.plugins.WaitingSkipReason.CAUSE;
import org.jdownloader.plugins.components.usenet.UsenetAccountConfigInterface;
import org.jdownloader.plugins.components.usenet.UsenetServer;
import org.jdownloader.plugins.controller.host.LazyHostPlugin.FEATURE;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.components.MultiHosterManagement;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "high-way.me" }, urls = { "https?://high\\-way\\.me/onlinetv\\.php\\?id=\\d+[^/]+|https?://[a-z0-9\\-\\.]+\\.high\\-way\\.me/dlu/[a-z0-9]+/[^/]+" })
public class HighWayMe extends UseNet {
    /** General API information: According to admin we can 'hammer' the API every 60 seconds */
    /* API docs: https://high-way.me/threads/highway-api.201/ */
    private static final String                   API_BASE                            = "https://high-way.me/api.php";
    // private static final String API_BASE = "http://http.high-way.me/api.php";
    private static MultiHosterManagement          mhm                                 = new MultiHosterManagement("high-way.me");
    private static final String                   NORESUME                            = "NORESUME";
    private static final String                   TYPE_TV                             = ".+high\\-way\\.me/onlinetv\\.php\\?id=.+";
    private static final String                   TYPE_DIRECT                         = ".+high\\-way\\.me/dlu/[a-z0-9]+/[^/]+";
    private static final int                      ERRORHANDLING_MAXLOGINS             = 2;
    private static final int                      STATUSCODE_PASSWORD_NEEDED_OR_WRONG = 13;
    private static final long                     trust_cookie_age                    = 300000l;
    /* Contains <host><Boolean resume possible|impossible> */
    private static HashMap<String, Boolean>       hostResumeMap                       = new HashMap<String, Boolean>();
    /* Contains <host><number of max possible chunks per download> */
    private static HashMap<String, Integer>       hostMaxchunksMap                    = new HashMap<String, Integer>();
    /* Contains <host><number of max possible simultan downloads> */
    private static HashMap<String, Integer>       hostMaxdlsMap                       = new HashMap<String, Integer>();
    /* Contains <host><number of currently running simultan downloads> */
    private static HashMap<String, AtomicInteger> hostRunningDlsNumMap                = new HashMap<String, AtomicInteger>();
    private static HashMap<String, Integer>       hostRabattMap                       = new HashMap<String, Integer>();
    private static Object                         UPDATELOCK                          = new Object();
    private static final int                      defaultMAXCHUNKS                    = -4;
    private static final boolean                  defaultRESUME                       = false;
    private int                                   statuscode                          = 0;

    public static interface HighWayMeConfigInterface extends UsenetAccountConfigInterface {
    };

    public HighWayMe(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://high-way.me/pages/premium");
    }

    @Override
    public String getAGBLink() {
        return "https://high-way.me/help/terms";
    }

    private Browser newBrowser() {
        br = new Browser();
        br.setCookiesExclusive(true);
        br.getHeaders().put("User-Agent", "JDownloader");
        br.setCustomCharset("utf-8");
        br.setFollowRedirects(true);
        return br;
    }

    @Override
    public void update(final DownloadLink link, final Account account, long bytesTransfered) throws PluginException {
        synchronized (UPDATELOCK) {
            final String currentHost = this.correctHost(link.getHost());
            final Integer rabatt = hostRabattMap.get(currentHost);
            if (rabatt != null) {
                bytesTransfered = (bytesTransfered * (100 - rabatt)) / 100;
            }
        }
        super.update(link, account, bytesTransfered);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        if (isUsenetLink(link)) {
            return super.requestFileInformation(link);
        } else if (link.getDownloadURL().matches(TYPE_TV)) {
            final boolean check_via_json = true;
            final String dlink = Encoding.urlDecode(link.getDownloadURL(), true);
            final String linkid = new Regex(dlink, "id=(\\d+)").getMatch(0);
            link.setName(linkid);
            link.setLinkID(linkid);
            link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
            br.setFollowRedirects(true);
            ArrayList<Account> accs = AccountController.getInstance().getValidAccounts(this.getHost());
            if (accs == null || accs.size() == 0) {
                link.getLinkStatus().setStatusText("Only downlodable via account!");
                return AvailableStatus.UNCHECKABLE;
            }
            URLConnectionAdapter con = null;
            long filesize = -1;
            String filesize_str;
            String filename = null;
            for (Account acc : accs) {
                this.loginSafe(acc, false);
                if (check_via_json) {
                    final String json_url = link.getDownloadURL().replaceAll("stream=(?:0|1)", "") + "&json=1";
                    this.br.getPage(json_url);
                    final String code = PluginJSonUtils.getJsonValue(this.br, "code");
                    filename = PluginJSonUtils.getJsonValue(this.br, "name");
                    filesize_str = PluginJSonUtils.getJsonValue(this.br, "size");
                    if ("5".equals(code)) {
                        /* Login issue */
                        return AvailableStatus.UNCHECKABLE;
                    } else if (!"0".equals(code)) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                    if (StringUtils.isEmpty(filename) || StringUtils.isEmpty(filesize_str) || !filesize_str.matches("\\d+")) {
                        /* This should never happen at this stage! */
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    filesize = Long.parseLong(filesize_str);
                    link.setDownloadSize(filesize);
                    break;
                } else {
                    try {
                        con = br.openHeadConnection(dlink);
                        if (this.looksLikeDownloadableContent(con)) {
                            filesize = con.getCompleteContentLength();
                            if (filesize <= 0) {
                                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                            } else {
                                filename = getFileNameFromHeader(con);
                                link.setVerifiedFileSize(filesize);
                            }
                        }
                    } finally {
                        try {
                            con.disconnect();
                        } catch (Throwable e) {
                        }
                    }
                    break;
                }
            }
            /* 2017-05-18: Even via json API, filenames are often html encoded --> Fix that */
            filename = Encoding.htmlDecode(filename);
            link.setFinalFileName(filename);
        } else {
            /* Direct URLs (e.g. Usenet Downloads) - downloadable even without account. */
            URLConnectionAdapter con = null;
            try {
                final Browser brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                con = brc.openHeadConnection(link.getDownloadURL());
                if (!this.looksLikeDownloadableContent(con)) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                final long filesize = con.getCompleteContentLength();
                if (filesize <= 0) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                link.setFinalFileName(getFileNameFromHeader(con));
                link.setVerifiedFileSize(filesize);
            } finally {
                try {
                    con.disconnect();
                } catch (Throwable e) {
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public boolean canHandle(final DownloadLink link, final Account account) throws Exception {
        if (account != null && link.getPluginPatternMatcher().matches(TYPE_DIRECT)) {
            /* This is the only linktype which is downloadable via account */
            return true;
        } else if (account == null) {
            /* without account its not possible to download the link */
            return false;
        }
        /* Make sure that we do not start more than the allowed number of max simultan downloads for the current host. */
        synchronized (UPDATELOCK) {
            final String currentHost = this.correctHost(link.getHost());
            if (hostRunningDlsNumMap.containsKey(currentHost) && hostMaxdlsMap.containsKey(currentHost)) {
                final int maxDlsForCurrentHost = hostMaxdlsMap.get(currentHost);
                final AtomicInteger currentRunningDlsForCurrentHost = hostRunningDlsNumMap.get(currentHost);
                if (currentRunningDlsForCurrentHost.get() >= maxDlsForCurrentHost) {
                    /*
                     * Max downloads for specific host for this MOCH reached --> Avoid irritating/wrong 'Account missing' errormessage for
                     * this case - wait and retry!
                     */
                    throw new ConditionalSkipReasonException(new WaitingSkipReason(CAUSE.HOST_TEMP_UNAVAILABLE, 15 * 1000, null));
                }
            }
        }
        return true;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, link.getDownloadURL(), true, defaultMAXCHUNKS);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            logger.warning("The final dllink seems not to be a file!");
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 30 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 60 * 60 * 1000l);
            }
        }
        dl.startDownload();
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        this.loginSafe(account, false);
        if (isUsenetLink(link)) {
            super.handleMultiHost(link, account);
            return;
        } else {
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, link.getDownloadURL(), true, defaultMAXCHUNKS);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                logger.warning("The final dllink seems not to be a file!");
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                if (dl.getConnection().getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 30 * 60 * 1000l);
                } else if (dl.getConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
                } else {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 60 * 60 * 1000l);
                }
            }
            dl.startDownload();
        }
    }

    @SuppressWarnings("deprecation")
    private void handleDL(final Account account, final DownloadLink link, final String dllink) throws Exception {
        /* we want to follow redirects in final stage */
        br.setFollowRedirects(true);
        boolean resume = account.getBooleanProperty("resume", defaultRESUME);
        int maxChunks = account.getIntegerProperty("account_maxchunks", defaultMAXCHUNKS);
        final String thishost = link.getHost();
        synchronized (UPDATELOCK) {
            if (hostMaxchunksMap.containsKey(thishost)) {
                maxChunks = hostMaxchunksMap.get(thishost);
            }
            if (hostResumeMap.containsKey(thishost)) {
                resume = hostResumeMap.get(thishost);
            }
        }
        if (link.getBooleanProperty(this.getHost() + NORESUME, false)) {
            resume = false;
        }
        if (!resume) {
            maxChunks = 1;
        }
        link.setProperty(this.getHost() + "directlink", dllink);
        br.setAllowedResponseCodes(new int[] { 503 });
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resume, maxChunks);
        final long responsecode = dl.getConnection().getResponseCode();
        if (responsecode == 416) {
            logger.info("Resume impossible, disabling it for the next try");
            link.setChunksProgress(null);
            link.setProperty(this.getHost() + NORESUME, Boolean.valueOf(true));
            throw new PluginException(LinkStatus.ERROR_RETRY);
        }
        jd.plugins.hoster.SimplyPremiumCom.handle503(this.br, responsecode);
        dl.setFilenameFix(true);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            /* 2020-06-03: E.g. cache/serverside download handling/status */
            final String retry_in_secondsStr = PluginJSonUtils.getJson(br, "retry_in_seconds");
            if (retry_in_secondsStr != null && retry_in_secondsStr.matches("\\d+")) {
                String msg = PluginJSonUtils.getJson(br, "for_jd");
                if (StringUtils.isEmpty(msg)) {
                    msg = "Cache handling retry";
                }
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, msg, Integer.parseInt(retry_in_secondsStr) * 1000l);
            }
            mhm.handleErrorGeneric(account, this.getDownloadLink(), "unknowndlerror", 10, 5 * 60 * 1000l);
        }
        try {
            controlSlot(+1);
            this.dl.startDownload();
        } finally {
            // remove usedHost slot from hostMap
            // remove download slot
            controlSlot(-1);
        }
    }

    @Override
    public FEATURE[] getFeatures() {
        return new FEATURE[] { FEATURE.MULTIHOST, FEATURE.USENET };
    }

    @Override
    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {
        this.br = newBrowser();
        /*
         * When JD is started the first time and the user starts downloads right away, a full login might not yet have happened but it is
         * needed to get the individual host limits.
         */
        synchronized (UPDATELOCK) {
            if (hostMaxchunksMap.isEmpty() || hostMaxdlsMap.isEmpty()) {
                logger.info("Performing full login to set individual host limits");
                this.fetchAccountInfo(account);
            }
        }
        if (isUsenetLink(link)) {
            super.handleMultiHost(link, account);
            return;
        } else {
            mhm.runCheck(account, link);
            String dllink = checkDirectLink(link, this.getHost() + "directlink");
            if (dllink == null) {
                /* request creation of downloadlink */
                br.setFollowRedirects(true);
                /* 2019-09-20: Does not matter if this is null! */
                String passCode = Encoding.urlEncode(link.getDownloadPassword());
                int counter = 0;
                do {
                    if (counter > 0) {
                        passCode = getUserInput("Password?", link);
                    }
                    getPageAndEnsureLogin(account, "https://high-way.me/load.php?json&link=" + Encoding.urlEncode(link.getDefaultPlugin().buildExternalDownloadURL(link, this)) + "&pass=" + Encoding.urlEncode(passCode));
                    counter++;
                } while (this.statuscode == STATUSCODE_PASSWORD_NEEDED_OR_WRONG && counter <= 2);
                if (this.statuscode == STATUSCODE_PASSWORD_NEEDED_OR_WRONG) {
                    link.setDownloadPassword(null);
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
                }
                dllink = PluginJSonUtils.getJsonValue(br, "download");
                String hash = PluginJSonUtils.getJsonValue(br, "hash");
                if (hash != null && hash.matches("md5:[a-f0-9]{32}")) {
                    hash = hash.substring(hash.lastIndexOf(":") + 1);
                    link.setMD5Hash(hash);
                }
                if (dllink == null) {
                    logger.warning("Final downloadlink is null");
                    mhm.handleErrorGeneric(account, this.getDownloadLink(), "dllinknull", 50, 5 * 60 * 1000l);
                }
                dllink = Encoding.htmlDecode(dllink);
            }
            handleDL(account, link, dllink);
        }
    }

    /**
     * Performs request first without checking login and if that fails, again with ensuring login! This saves us http requests and time!
     */
    private void getPageAndEnsureLogin(final Account account, final String url) throws Exception {
        boolean verifiedCookies = this.login(account, false);
        this.br.getPage(url);
        /** TODO: Add isLoggedIN function and check */
        if (!verifiedCookies && !this.isLoggedIN()) {
            logger.info("Retrying with ensured login");
            verifiedCookies = this.login(account, false);
            this.br.getPage(url);
            if (!this.isLoggedIN()) {
                /* This should never happen! */
                logger.warning("Potential login failure");
            }
        }
        updatestatuscode();
        handleAPIErrors(this.br, account);
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
                    return dllink;
                } else {
                    throw new IOException();
                }
            } catch (final Exception e) {
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

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        this.br = newBrowser();
        final AccountInfo ai = new AccountInfo();
        this.login(account, true);
        getAPISafe(account, API_BASE + "?hoster&user");
        final LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
        final LinkedHashMap<String, Object> info_account = (LinkedHashMap<String, Object>) entries.get("user");
        final ArrayList<Object> array_hoster = (ArrayList) entries.get("hoster");
        final int account_maxchunks = ((Number) info_account.get("max_chunks")).intValue();
        int account_maxdls = ((Number) info_account.get("max_connection")).intValue();
        account_maxdls = this.correctMaxdls(account_maxdls);
        final int account_resume = ((Number) info_account.get("resume")).intValue();
        /* TODO: Real traffic is missing. */
        final long free_traffic_max_daily = ((Number) info_account.get("free_traffic")).longValue();
        long free_traffic_left = ((Number) info_account.get("remain_free_traffic")).longValue();
        final long premium_bis = ((Number) info_account.get("premium_bis")).longValue();
        final long premium_traffic = ((Number) info_account.get("premium_traffic")).longValue();
        final long premium_traffic_max = ((Number) info_account.get("premium_max")).longValue();
        /* Set account type and related things */
        if (premium_bis > 0 && premium_traffic_max > 0) {
            ai.setTrafficLeft(premium_traffic);
            ai.setTrafficMax(premium_traffic_max);
            ai.setValidUntil(premium_bis * 1000, this.br);
            account.setType(AccountType.PREMIUM);
            ai.setStatus("Premium account");
        } else {
            if (free_traffic_left > free_traffic_max_daily) {
                /* User has more traffic than downloadable daily for free users --> Show max daily traffic. */
                ai.setTrafficLeft(free_traffic_max_daily);
                ai.setTrafficMax(free_traffic_max_daily);
            } else {
                /* User has less traffic than downloadable daily for free users --> Show real traffic left. */
                ai.setTrafficLeft(free_traffic_left);
                ai.setTrafficMax(free_traffic_max_daily);
            }
            account.setType(AccountType.FREE);
            ai.setStatus("Registered (free) account");
        }
        account.setConcurrentUsePossible(true);
        /* Set supported hosts, limits and account limits */
        account.setProperty("account_maxchunks", this.correctChunks(account_maxchunks));
        account.setMaxSimultanDownloads(account_maxdls);
        if (account_resume == 1) {
            account.setProperty("resume", true);
        } else {
            account.setProperty("resume", false);
        }
        final ArrayList<String> supportedHosts = new ArrayList<String>();
        synchronized (UPDATELOCK) {
            hostMaxchunksMap.clear();
            hostRabattMap.clear();
            hostMaxdlsMap.clear();
            account.setMaxSimultanDownloads(account_maxdls);
            for (final Object hoster : array_hoster) {
                final LinkedHashMap<String, Object> hoster_map = (LinkedHashMap<String, Object>) hoster;
                final String domain = correctHost((String) hoster_map.get("name"));
                final String active = (String) hoster_map.get("active");
                final int resume = Integer.parseInt((String) hoster_map.get("resume"));
                final int maxchunks = Integer.parseInt((String) hoster_map.get("chunks"));
                final int maxdls = Integer.parseInt((String) hoster_map.get("downloads"));
                final int rabatt = Integer.parseInt((String) hoster_map.get("rabatt"));
                // final String unlimited = (String) hoster_map.get("unlimited");
                hostRabattMap.put(domain, rabatt);
                if (active.equals("1")) {
                    supportedHosts.add(domain);
                    hostMaxchunksMap.put(domain, correctChunks(maxchunks));
                    hostMaxdlsMap.put(domain, correctMaxdls(maxdls));
                    if (resume == 0) {
                        hostResumeMap.put(domain, false);
                    } else {
                        hostResumeMap.put(domain, true);
                    }
                }
            }
        }
        final Map<String, Object> usenetLogins = (Map<String, Object>) info_account.get("usenet");
        if (usenetLogins != null) {
            final String usenetUsername = (String) usenetLogins.get("username");
            final String usenetPassword = (String) usenetLogins.get("pass");
            ai.setProperty("usenetU", usenetUsername);
            ai.setProperty("usenetP", usenetPassword);
        } else {
            supportedHosts.remove("usenet");
            supportedHosts.remove("Usenet");
        }
        ai.setMultiHostSupport(this, supportedHosts);
        return ai;
    }

    @Override
    protected String getUseNetUsername(Account account) {
        final AccountInfo ai = account.getAccountInfo();
        if (ai != null) {
            return ai.getStringProperty("usenetU", null);
        }
        return null;
    }

    @Override
    protected String getUseNetPassword(Account account) {
        final AccountInfo ai = account.getAccountInfo();
        if (ai != null) {
            return ai.getStringProperty("usenetP", null);
        }
        return null;
    }

    /**
     * Login without errorhandling
     *
     * @return true = cookies validated </br>
     *         false = cookies set but not validated
     *
     * @throws PluginException
     */
    private boolean login(final Account account, final boolean validateCookies) throws IOException, PluginException {
        final Cookies cookies = account.loadCookies("");
        boolean loggedIN = false;
        if (cookies != null) {
            this.br.setCookies(this.getHost(), cookies);
            if (System.currentTimeMillis() - account.getCookiesTimeStamp("") <= trust_cookie_age && !validateCookies) {
                /* We trust these (new) cookies --> Do not check them */
                return false;
            }
            this.br.getPage(API_BASE + "?logincheck");
            loggedIN = this.isLoggedIN();
        }
        if (!loggedIN) {
            logger.info("Performing full login");
            br.postPage(API_BASE + "?login", "pass=" + Encoding.urlEncode(account.getPass()) + "&user=" + Encoding.urlEncode(account.getUser()));
            if (!isLoggedIN()) {
                accountInvalid();
            }
        }
        account.saveCookies(this.br.getCookies(this.br.getHost()), "");
        return true;
    }

    private boolean isLoggedIN() {
        return br.getCookie(br.getURL(), "xf_user", Cookies.NOTDELETEDPATTERN) != null;
    }

    /**
     * Login + errorhandling
     *
     * @throws InterruptedException
     */
    private void loginSafe(final Account account, final boolean force) throws IOException, PluginException, InterruptedException {
        login(account, force);
        updatestatuscode();
        handleAPIErrors(this.br, account);
    }

    private void getAPISafe(final Account account, final String accesslink) throws IOException, PluginException, InterruptedException {
        int tries = 0;
        do {
            this.br.getPage(accesslink);
            handleLoginIssues(account);
            tries++;
        } while (tries <= ERRORHANDLING_MAXLOGINS && this.statuscode == 9);
        updatestatuscode();
        handleAPIErrors(this.br, account);
    }

    private void postAPISafe(final Account account, final String accesslink, final String postdata) throws IOException, PluginException, InterruptedException {
        int tries = 0;
        do {
            this.br.postPage(accesslink, postdata);
            handleLoginIssues(account);
            tries++;
        } while (tries <= ERRORHANDLING_MAXLOGINS && this.statuscode == 9);
        updatestatuscode();
        handleAPIErrors(this.br, account);
    }

    /**
     * Performs full logins on errorcode 9 up to ERRORHANDLING_MAXLOGINS-times, hopefully avoiding login/cookie problems.
     *
     * @throws PluginException
     */
    private void handleLoginIssues(final Account account) throws IOException, PluginException {
        updatestatuscode();
        if (this.statuscode == 9) {
            this.login(account, true);
            updatestatuscode();
        }
    }

    /** Corrects input so that it fits what we use in our plugins. */
    private int correctChunks(int maxchunks) {
        if (maxchunks < 1) {
            maxchunks = 1;
        } else if (maxchunks > 20) {
            maxchunks = 20;
        } else if (maxchunks > 1) {
            maxchunks = -maxchunks;
        }
        /* Else maxchunks = 1 */
        return maxchunks;
    }

    /** Corrects input so that it fits what we use in our plugins. */
    private int correctMaxdls(int maxdls) {
        if (maxdls < 1) {
            maxdls = 1;
        } else if (maxdls > 20) {
            maxdls = 20;
        }
        /* Else we should have a valid value! */
        return maxdls;
    }

    /** Performs slight domain corrections. */
    private String correctHost(String host) {
        if (host.equals("uploaded.to") || host.equals("uploaded.net")) {
            host = "uploaded.to";
        }
        return host;
    }

    /**
     * Prevents more than one free download from starting at a given time. One step prior to dl.startDownload(), it adds a slot to maxFree
     * which allows the next singleton download to start, or at least try.
     *
     * This is needed because xfileshare(website) only throws errors after a final dllink starts transferring or at a given step within pre
     * download sequence. But this template(XfileSharingProBasic) allows multiple slots(when available) to commence the download sequence,
     * this.setstartintival does not resolve this issue. Which results in x(20) captcha events all at once and only allows one download to
     * start. This prevents wasting peoples time and effort on captcha solving and|or wasting captcha trading credits. Users will experience
     * minimal harm to downloading as slots are freed up soon as current download begins.
     *
     * @param controlSlot
     *            (+1|-1)
     */
    private void controlSlot(final int num) {
        synchronized (UPDATELOCK) {
            final String currentHost = correctHost(this.getDownloadLink().getHost());
            AtomicInteger currentRunningDls = new AtomicInteger(0);
            if (hostRunningDlsNumMap.containsKey(currentHost)) {
                currentRunningDls = hostRunningDlsNumMap.get(currentHost);
            }
            currentRunningDls.set(currentRunningDls.get() + num);
            hostRunningDlsNumMap.put(currentHost, currentRunningDls);
        }
    }

    /**
     * 0 = everything ok, 1-99 = official errorcodes, 100-199 = login-errors, 200-299 = info-states, 666 = hell
     */
    private void updatestatuscode() {
        final String waittime_on_failure = PluginJSonUtils.getJsonValue(br, "timeout");
        /* First look for errorcode */
        String error = PluginJSonUtils.getJsonValue(br, "code");
        if (error == null) {
            /* No errorcode? Look for errormessage (e.g. used in login function). */
            error = PluginJSonUtils.getJsonValue(br, "error");
        }
        final String info = PluginJSonUtils.getJsonValue(br, "info");
        if (error != null) {
            if (error.matches("\\d+")) {
                statuscode = Integer.parseInt(error);
            } else {
                if (error.equals("NotLoggedIn")) {
                    statuscode = 100;
                } else if (error.equals("UserOrPassInvalid")) {
                    statuscode = 100;
                }
            }
        } else if ("Traffic ist kleiner als 10%".equals(info)) {
            statuscode = 200;
        } else {
            statuscode = 0;
        }
    }

    private void handleAPIErrors(final Browser br, final Account account) throws PluginException, InterruptedException {
        final String lang = System.getProperty("user.language");
        String statusMessage = PluginJSonUtils.getJson(br, "error");
        if (StringUtils.isEmpty(statusMessage)) {
            statusMessage = "Unknown error";
        }
        try {
            switch (statuscode) {
            case 0:
                /* Everything ok */
                break;
            case 1:
                /* Login or password missing -> disable account */
                throw new PluginException(LinkStatus.ERROR_PREMIUM, statusMessage, PluginException.VALUE_ID_PREMIUM_DISABLE);
            case 2:
                /* Not enough free traffic */
                throw new PluginException(LinkStatus.ERROR_PREMIUM, statusMessage, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            case 3:
                /* Not enough premium traffic */
                throw new PluginException(LinkStatus.ERROR_PREMIUM, statusMessage, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            case 4:
                /* Too many simultaneous downloads */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, statusMessage, 1 * 60 * 1000l);
            case 5:
                /* Login or password missing -> disable account */
                throw new PluginException(LinkStatus.ERROR_PREMIUM, statusMessage, PluginException.VALUE_ID_PREMIUM_DISABLE);
            case 6:
                /* Invalid link --> Disable host --> This should never happen */
                mhm.handleErrorGeneric(account, this.getDownloadLink(), statusMessage, 5, 5 * 60 * 1000l);
            case 7:
                /* 2020-08-11: This errorcode does not exist anymore serverside - it should never happen! RE: admin */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, statusMessage);
            case 8:
                /* Temp error, try again in some minutes */
                mhm.handleErrorGeneric(account, this.getDownloadLink(), statusMessage, 5, 5 * 60 * 1000l);
            case 9:
                /* File not found --> Do not trust this errormessage */
                /* Override serverside errormessage */
                statusMessage = "File not found (?)";
                mhm.putError(account, this.getDownloadLink(), 5 * 60 * 1000l, statusMessage);
            case 10:
                /* Host offline or invalid url -> Skip to next download candidate */
                mhm.putError(account, this.getDownloadLink(), 5 * 60 * 1000l, statusMessage);
            case 11:
                /* Host itself is currently unavailable (maintenance) -> Disable host */
                mhm.handleErrorGeneric(account, this.getDownloadLink(), statusMessage, 5, 5 * 60 * 1000l);
            case 12:
                /* MOCH itself is under maintenance --> Temp. disable account */
                if ("de".equalsIgnoreCase(lang)) {
                    statusMessage = "\r\nHigh-way führt momentan Wartungsarbeiten durch!";
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, statusMessage, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
                } else {
                    statusMessage = "\r\nHigh-way is doing maintenance work!";
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, statusMessage, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
                }
            case 13:
                /* Download password for filehost needed - this should be handled via upper code - do not do anything here! */
                break;
            case 14:
                /*
                 * Host-specified traffic limit reached e.g. traffic for keep2share.cc is empty but account still has traffic left for other
                 * hosts.
                 */
                mhm.putError(account, this.getDownloadLink(), 5 * 60 * 1000l, statusMessage);
            case 15:
                /* 2021-05-06: Host specific downloadlimit has been reached -> Disable LINK for 30 minutes (RE: admin) */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, statusMessage, 30 * 60 * 1000l);
            case 16:
                /* 2021-05-06: Multihost side error, user should contact multihost support RE: admin */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, statusMessage, 30 * 60 * 1000l);
            case 100:
                /* Login or password missing -> disable account */
                accountInvalid();
            case 200:
                if (!org.appwork.utils.Application.isHeadless()) {
                    BubbleNotify.getInstance().show(new AbstractNotifyWindowFactory() {
                        @Override
                        public AbstractNotifyWindow<?> buildAbstractNotifyWindow() {
                            return new BasicNotify("Weniger als 10% Traffic verbleibend", "Weniger als 10% Traffic verbleibend", new AbstractIcon(IconKey.ICON_INFO, 32));
                        }
                    });
                }
            case 666:
                /* Unknown error */
                statusMessage = "Unknown error";
                mhm.handleErrorGeneric(account, this.getDownloadLink(), "unknown_api_error", 50, 5 * 60 * 1000l);
            }
        } catch (final PluginException e) {
            logger.info(this.getHost() + ": Exception: statusCode: " + statuscode + " statusMessage: " + statusMessage);
            throw e;
        }
    }

    private void accountInvalid() throws PluginException {
        if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername/Passwort!\r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen? Versuche folgendes:\r\n1. Falls dein Passwort Sonderzeichen enthält, ändere es (entferne diese) und versuche es erneut!\r\n2. Falls du die 2-Faktor-Authentifizierung aktiviert hast, deaktiviere diese und versuche es erneut.\r\n3. Gib deine Zugangsdaten per Hand (ohne kopieren/einfügen) ein.", PluginException.VALUE_ID_PREMIUM_DISABLE);
        } else {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nYou're sure that the username and password you entered are correct? Some hints:\r\n1. If your password contains special characters, change it (remove them) and try again!\r\n2. If you have 2-factor-authentication enabled, disable it and try again.\r\n3. Type in your username/password by hand without copy & paste.", PluginException.VALUE_ID_PREMIUM_DISABLE);
        }
    }

    @Override
    public int getMaxSimultanDownload(final DownloadLink link, final Account account) {
        if (account != null) {
            if (isUsenetLink(link)) {
                return 5;
            } else {
                if (link == null) {
                    return account.getMaxSimultanDownloads();
                } else if (link.getDownloadURL().matches(TYPE_DIRECT)) {
                    return 5;
                } else {
                    final String currentHost = correctHost(link.getHost());
                    synchronized (UPDATELOCK) {
                        if (hostMaxdlsMap.containsKey(currentHost)) {
                            return hostMaxdlsMap.get(currentHost);
                        }
                    }
                }
            }
        } else if (link != null && link.getDownloadURL().matches(TYPE_DIRECT)) {
            return 5;
        }
        return 1;
    }

    /** According to High-Way staff, Usenet SSL is unavailable since 2017-08-01 */
    @Override
    public List<UsenetServer> getAvailableUsenetServer() {
        final List<UsenetServer> ret = new ArrayList<UsenetServer>();
        ret.addAll(UsenetServer.createServerList("reader.high-way.me", false, 119));
        ret.addAll(UsenetServer.createServerList("reader.high-way.me", true, 563));
        return ret;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}