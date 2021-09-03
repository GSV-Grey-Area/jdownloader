package jd.plugins.hoster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.plugins.components.usenet.UsenetAccountConfigInterface;
import org.jdownloader.plugins.components.usenet.UsenetServer;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.parser.html.Form.MethodType;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "eweka.nl" }, urls = { "" })
public class UseNetEwekaNl extends UseNet {
    public UseNetEwekaNl(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.eweka.nl/en/usenet_toegang/specificaties/");
    }

    @Override
    public String getAGBLink() {
        return "https://www.eweka.nl/en/av/";
    }

    public static interface EwekaNlConfigInterface extends UsenetAccountConfigInterface {
    };

    private final String USENET_USERNAME = "USENET_USERNAME";

    @Override
    protected String getUseNetUsername(Account account) {
        return account.getStringProperty(USENET_USERNAME, account.getUser());
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        setBrowserExclusive();
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        // final String server = br.getRegex("<td><b>Server</b></td>.*?<td.*?>(.*?)</td>").getMatch(0);
        // final String port = br.getRegex("<td><b>Port</b></td>.*?<td.*?>(\\d+)</td>").getMatch(0);
        // TODO: use these infos for available servers
        getPage("/myeweka?p=acd");
        final String connections = br.getRegex("<td><b>Connections</b></td>.*?<td.*?>(\\d+)</td>").getMatch(0);
        if (connections != null) {
            account.setMaxSimultanDownloads(Integer.parseInt(connections));
        } else {
            /* Fallback */
            account.setMaxSimultanDownloads(8);
        }
        String userName = br.getRegex("name=\"username\" value=\"([^<>\"]+)\"").getMatch(0);
        if (userName == null) {
            /* Final fallback */
            userName = account.getUser();
        }
        if (userName == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else {
            account.setProperty(USENET_USERNAME, userName);
        }
        String validUntil = br.getRegex("<td><b>Valid until</b></td>.*?<td.*?>\\s*?(\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2})").getMatch(0);
        if (validUntil == null) {
            /* 2020-01-21 */
            validUntil = br.getRegex(">Next billing at</b></td>\\s*<td>(\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2})").getMatch(0);
        }
        if (validUntil == null) {
            /* 2020-01-21 - wide open RegEx as fallback */
            validUntil = br.getRegex("(\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2})").getMatch(0);
        }
        if (validUntil != null) {
            final long date = TimeFormatter.getMilliSeconds(validUntil, "dd'-'MM'-'yyyy' 'HH:mm", null);
            if (date > 0) {
                ai.setValidUntil(date, br);
            }
            account.setType(AccountType.PREMIUM);
        } else {
            account.setType(AccountType.FREE);
            ai.setTrafficLeft(0);
        }
        ai.setProperty("multiHostSupport", Arrays.asList(new String[] { "usenet" }));
        return ai;
    }

    private void login(final Account account, final boolean verifyCookies) throws Exception {
        br.setFollowRedirects(true);
        final Cookies cookies = account.loadCookies("");
        /* 2021-09-03: Added cookie login as possible workaround for them using Cloudflare on login page. */
        final Cookies userCookies = Cookies.parseCookiesFromJsonString(account.getPass(), getLogger());
        if (cookies != null) {
            if (!verifyCookies) {
                logger.info("Trust login cookies without check");
                br.setCookies(cookies);
                return;
            }
            logger.info("Checking login cookies");
            if (checkLogin(br, cookies)) {
                logger.info("Failed to login via cookies");
                /* Delete old cookies */
                account.clearCookies("");
                br.getCookies(getHost()).clear();
            } else {
                logger.info("Successfully loggedin via cookies");
                account.saveCookies(br.getCookies(getHost()), "");
                return;
            }
        }
        if (userCookies != null) {
            logger.info("Checking login user cookies");
            if (checkLogin(br, userCookies)) {
                logger.info("Successfully loggedin via user cookies");
                account.saveCookies(br.getCookies(getHost()), "");
                return;
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "Cookie login failed", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        }
        logger.info("Performing full login");
        getPage("https://www." + this.getHost() + "/myeweka/?lang=en");
        final Form loginform = br.getFormbyProperty("id", "login-form");
        if (loginform == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        loginform.setMethod(MethodType.POST);
        loginform.put("identifier", Encoding.urlEncode(account.getUser()));
        loginform.put("password", Encoding.urlEncode(account.getPass()));
        submitForm(loginform);
        if (!isLoggedIN(br)) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
        }
        account.saveCookies(br.getCookies(getHost()), "");
    }

    private boolean checkLogin(final Browser br, final Cookies cookies) throws Exception {
        br.setCookies(cookies);
        getPage("https://www." + this.getHost() + "/en/myeweka?p=pro");
        if (isLoggedIN(br)) {
            return true;
        } else {
            return false;
        }
    }

    private boolean isLoggedIN(final Browser br) {
        String logintoken = br.getCookie(getHost(), "auth-token", Cookies.NOTDELETEDPATTERN);
        return !StringUtils.isEmpty(logintoken) && !logintoken.equals("\"\"");
    }

    @Override
    public List<UsenetServer> getAvailableUsenetServer() {
        final List<UsenetServer> ret = new ArrayList<UsenetServer>();
        ret.addAll(UsenetServer.createServerList("newsreader.eweka.nl", false, 119));// resolves to 3 IP
        // ret.addAll(UsenetServer.createServerList("newsreader124.eweka.nl", false, 119));//resolves to 1 IP
        ret.addAll(UsenetServer.createServerList("sslreader.eweka.nl", true, 563, 443));// resolves to 3 IP
        return ret;
    }
}
