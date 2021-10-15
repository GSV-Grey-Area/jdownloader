//jDownloader - Downloadmanager
//Copyright (C) 2011  JD-Team support@jdownloader.org
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

import org.jdownloader.plugins.components.FlexShareCore;

import jd.PluginWrapper;
import jd.http.Cookies;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class FilePupNet extends FlexShareCore {
    public FilePupNet(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://" + this.getHost() + "/get-premium.php");
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "filepup.net" });
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
        return FlexShareCore.buildAnnotationUrls(getPluginDomains());
    }

    @Override
    protected String getAPIKey() {
        return "vwUhhGH6lPH3auk6SM144PBg3PRQg";
    }

    @Override
    public int getMaxChunks(final Account account) {
        final AccountType type = account != null ? account.getType() : null;
        if (AccountType.FREE.equals(type)) {
            /* Free Account */
            return 1;
        } else if (AccountType.PREMIUM.equals(type) || AccountType.LIFETIME.equals(type)) {
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

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    protected boolean supports_https() {
        /* 2019-10-02: Special */
        return false;
    }

    @Override
    protected String getLoginURL() {
        return getMainPage() + "/loginaa.php";
    }

    // @Override
    // protected Form getAndFillLoginForm(final Account account) {
    // /* 2019-10-02: Special */
    // final Form loginform = br.getFormbyActionRegex(".*loginaa\\.php");
    // if (loginform == null) {
    // return null;
    // }
    // loginform.put("user", account.getUser());
    // loginform.put("pass", account.getPass());
    // return loginform;
    // }
    @Override
    protected boolean isLoggedIN() {
        /* 2019-10-02: Special */
        return br.getCookie(br.getHost(), "sid", Cookies.NOTDELETEDPATTERN) != null || br.containsHTML("/logout\\.php\"");
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    protected void handleErrors() throws PluginException {
        super.handleErrors();
        if (br.containsHTML("(?i)>\\s*This file does not exist")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
    }
}