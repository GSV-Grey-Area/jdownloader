//jDownloader - Downloadmanager
//Copyright (C) 2016  JD-Team support@jdownloader.org
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.plugins.components.YetiShareCore;

import jd.PluginWrapper;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class UploadshipCom extends YetiShareCore {
    public UploadshipCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(getPurchasePremiumURL());
    }

    /**
     * DEV NOTES YetiShare<br />
     ****************************
     * mods: See overridden functions<br />
     * limit-info: 2019-07-31: Premium untested, set FREE account limits <br />
     * captchatype-info: 2020-04-08: reCaptchaV2<br />
     * other: <br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "uploadship.com" });
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
        final List<String[]> pluginDomains = getPluginDomains();
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + YetiShareCore.getDefaultAnnotationPatternPart());
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return true;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return true;
        } else {
            /* Free(anonymous) and unknown account type */
            return true;
        }
    }

    public int getMaxChunks(final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return 1;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return 1;
        } else {
            /* Free(anonymous) and unknown account type */
            return 1;
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    public int getMaxSimultaneousFreeAccountDownloads() {
        return 1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return 1;
    }

    @Override
    public boolean supports_availablecheck_over_info_page(DownloadLink link) {
        /* 2019-07-31: Special */
        return false;
    }

    @Override
    protected boolean supports_availablecheck_filesize_html() {
        /* 2019-07-31: Special */
        return false;
    }

    @Override
    protected boolean isOfflineWebsite(final DownloadLink link) throws PluginException {
        return br.getHttpConnection().getResponseCode() == 404;
    }

    @Override
    public String[] scanInfo(DownloadLink link, final String[] fileInfo) {
        /* 2019-07-31: Special */
        fileInfo[0] = br.getRegex("<title>([^<>\"]+) \\- UploadShip</title>").getMatch(0);
        if (StringUtils.isEmpty(fileInfo[0])) {
            fileInfo[0] = br.getRegex("How To Free Download\\s*:\\s*<b>([^<>\"]+)<").getMatch(0);
        }
        if (fileInfo[0] != null && fileInfo[0].trim().equalsIgnoreCase("free download")) {
            /* 2020-10-20: Workaround: Some files don't have any name given until download is started */
            logger.info("No filename given for this file");
            fileInfo[0] = null;
        }
        fileInfo[1] = br.getRegex("\\[\\s*(\\d+)\\s*bytes\\s*\\]").getMatch(0);
        if (StringUtils.isEmpty(fileInfo[0]) || StringUtils.isEmpty(fileInfo[1])) {
            /* Use default handling as fallback */
            super.scanInfo(link, fileInfo);
        }
        return fileInfo;
    }

    @Override
    protected AccountInfo fetchAccountInfoWebsite(final Account account) throws Exception {
        final AccountInfo ai = super.fetchAccountInfoWebsite(account);
        if (account.getType() != AccountType.PREMIUM) {
            /* 2020-10-20: Special */
            if (br.getURL() == null || !br.getURL().contains("/account_home.html")) {
                getPage("/account_home.html");
            }
            String expireStr = br.getRegex("Your Premium Expiration Date\\s*:\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})").getMatch(0);
            if (expireStr == null) {
                /*
                 * 2019-03-01: As far as we know, EVERY premium account will have an expire-date given but we will still accept accounts for
                 * which we fail to find the expire-date.
                 */
                logger.info("Failed to find expire-date --> Probably a FREE account");
                setAccountLimitsByType(account, AccountType.FREE);
                return ai;
            }
            final long expire_milliseconds = TimeFormatter.getMilliSeconds(expireStr, "yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
            final boolean isPremium = expire_milliseconds > System.currentTimeMillis();
            /* If the premium account is expired we'll simply accept it as a free account. */
            if (!isPremium) {
                /* Expired premium == FREE */
                setAccountLimitsByType(account, AccountType.FREE);
                // ai.setStatus("Registered (free) user");
            } else {
                ai.setValidUntil(expire_milliseconds, this.br);
                setAccountLimitsByType(account, AccountType.PREMIUM);
                // ai.setStatus("Premium account");
            }
            ai.setUnlimitedTraffic();
        }
        return ai;
    }
}