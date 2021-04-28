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

import org.jdownloader.plugins.components.YetiShareCore;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class EraiDdlthreeInfo extends YetiShareCore {
    public EraiDdlthreeInfo(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(this.getPurchasePremiumURL());
    }

    /**
     * DEV NOTES YetiShare<br />
     ****************************
     * mods: See overridden functions<br />
     * limit-info 2020-09-14: No limits at all :<br />
     * captchatype-info: 2020-09-14: null<br />
     * other: 2020-11-12: Downloads are only possible via (free) account and only if URLs were added via crawler! <br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "erai-ddl3.info" });
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
        return EraiDdlthreeInfo.buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            /* 2020-09-14: Special:Allow subdomains */
            ret.add("https?://(?:[a-z0-9]+\\.)?" + YetiShareCore.buildHostsPatternPart(domains) + YetiShareCore.getDefaultAnnotationPatternPart());
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
            return 0;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return 0;
        } else {
            /* Free(anonymous) and unknown account type */
            return 0;
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    public int getMaxSimultaneousFreeAccountDownloads() {
        return -1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public boolean requires_WWW() {
        /* 2021-01-12 */
        return true;
    }

    @Override
    protected AccountInfo fetchAccountInfoWebsite(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        loginWebsite(account, true);
        if (br.getURL() == null || !br.getURL().contains("/account")) {
            getPage("/account");
        }
        if (!isPremiumAccount(account, this.br)) {
            logger.info("Looks like we have a free account");
            setAccountLimitsByType(account, AccountType.FREE);
        } else {
            setAccountLimitsByType(account, AccountType.PREMIUM);
        }
        return ai;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformationWebsite(link, null, true);
        /* 2020-11-12: Downloads without account are not possible anymore */
        throw new AccountRequiredException();
    }

    @Override
    public void checkErrors(final Browser br, final DownloadLink link, final Account account) throws PluginException {
        super.checkErrors(br, link, account);
        if (br.containsHTML(">\\s*Please enter your information to register for an account")) {
            throw new AccountRequiredException();
        }
    }

    @Override
    protected void checkErrorsLastResort(final Browser br, final DownloadLink link, final Account account) throws PluginException {
        if (getInternalFileID(link, br) == null) {
            /*
             * 2020-11-12: Cannot download without this ID! Needs to be set in crawler in beforehand!
             */
            throw new PluginException(LinkStatus.ERROR_FATAL, "Unable to download files that haven't been added as part of a folder");
        } else {
            super.checkErrorsLastResort(br, link, account);
        }
        final String rcID = br.getRegex("recaptcha/api/noscript\\?k=([^<>\"]*?)\"").getMatch(0);
        if (account != null) {
            this.loggedInOrException(br, account);
        } else if (new Regex(br.getURL(), "^https?://[^/]+/?$").matches()) {
            /* Handle redirect to mainpage as premiumonly */
            throw new AccountRequiredException();
        } else if (rcID != null) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "Website uses reCaptchaV1 which has been shut down by Google. Contact website owner!");
        }
        logger.warning("Unknown error happened");
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }
    // @Override
    // protected String getInternalFileID(final DownloadLink link, final Browser br) throws PluginException {
    // final String internalFileID = super.getInternalFileID(link, br);
    // if (internalFileID == null) {
    // /*
    // * 2020-11-12: Cannot download without this ID! Needs to be set in crawler in beforehand! --> This should never happen because
    // * of canHandle()!
    // */
    // throw new PluginException(LinkStatus.ERROR_FATAL, "Unable to download files that haven't been added as part of a folder");
    // } else {
    // return internalFileID;
    // }
    // }

    @Override
    public boolean canHandle(final DownloadLink link, final Account account) throws Exception {
        /**
         * 2020-11-12: Downloads without account are not possible anymore. Downloads are additionally only possible when this internal
         * fileID is given --> We handle this case inside getInternalFileID() .
         */
        return account != null;
    }
}