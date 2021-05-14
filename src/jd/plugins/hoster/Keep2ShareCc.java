//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
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

import jd.PluginWrapper;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.PluginForHost;

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.config.Keep2shareConfig;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "k2s.cc" }, urls = { "https?://(?:[a-z0-9\\-]+\\.)?(?:keep2share|k2s|k2share|keep2s|keep2)\\.cc/(?:file|preview)/(?:info/)?([a-z0-9_\\-]+)(/([^/\\?]+))?(\\?site=([^\\&]+))?" })
public class Keep2ShareCc extends K2SApi {
    public Keep2ShareCc(PluginWrapper wrapper) {
        super(wrapper);
    }

    public final String MAINTLD = "k2s.cc";

    // private final String DOMAINS_HTTP = "(https?://((www|new)\\.)?" + DOMAINS_PLAIN + ")";
    @Override
    public String[] siteSupportedNames() {
        // keep2.cc no dns
        return new String[] { "k2s.cc", "keep2share.cc", "keep2s.cc", "k2share.cc", "keep2share.com", "keep2share" };
    }

    @Override
    public String rewriteHost(String host) {
        if (host == null) {
            return "k2s.cc";
        }
        for (final String supportedName : siteSupportedNames()) {
            if (supportedName.equals(host)) {
                return "k2s.cc";
            }
        }
        return super.rewriteHost(host);
    }

    @Override
    public String buildExternalDownloadURL(final DownloadLink link, final PluginForHost buildForThisPlugin) {
        if (StringUtils.equals("real-debrid.com", buildForThisPlugin.getHost())) {
            return "http://k2s.cc/file/" + getFUID(link);// do not change
        } else {
            return link.getPluginPatternMatcher();
        }
    }

    @Override
    protected boolean isSpecialFUID(String fuid) {
        return fuid.contains("-") || fuid.contains("_");
    }

    @Override
    protected String getInternalAPIDomain() {
        return MAINTLD;
    }

    /**
     * easiest way to set variables, without the need for multiple declared references
     *
     * @param account
     */
    @Override
    protected void setConstants(final Account account) {
        super.setConstants(account);
        if (account != null) {
            if (account.getType() == AccountType.FREE) {
                // free account
                chunks = 1;
                resumes = true;
                isFree = true;
            } else {
                // premium account
                chunks = -10;
                resumes = true;
                isFree = false;
            }
            logger.finer("setConstants = " + account.getUser() + " @ Account Download :: isFree = " + isFree + ", upperChunks = " + chunks + ", Resumes = " + resumes);
        } else {
            // free non account
            chunks = 1;
            resumes = true;
            isFree = true;
            logger.finer("setConstants = Guest Download :: isFree = " + isFree + ", upperChunks = " + chunks + ", Resumes = " + resumes);
        }
    }

    @Override
    protected void setAccountLimits(Account account) {
        final int max;
        switch (account.getType()) {
        case PREMIUM:
            max = 20;
            break;
        default:
            max = 1;
            break;
        }
        maxPrem.set(max);
        account.setMaxSimultanDownloads(max);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return maxPrem.get();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return maxFree.get();
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
        super.resetLink(link);
    }

    @Override
    protected String getReCaptchaV2WebsiteKey() {
        return "6LcOsNIaAAAAABzCMnQw7u0u8zd1mrqY6ibFtto8";
    }

    @Override
    public Class<? extends Keep2shareConfig> getConfigInterface() {
        return Keep2shareConfig.class;
    }
}