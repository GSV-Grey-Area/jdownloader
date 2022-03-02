//jDownloader - Downloadmanager
//Copyright (C) 2012  JD-Team support@jdownloader.org
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

import jd.PluginWrapper;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class VideoFCTwoCom extends VideoFCTwoCore {
    public VideoFCTwoCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://secure.id.fc2.com/signup.php?ref=video");
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "video.fc2.com", "xiaojiadianvideo.asia", "jinniumovie.be" });
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
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://" + buildHostsPatternPart(domains) + "/((?:[a-z]{2}/)?(?:a/)?flv2\\.swf\\?i=|(?:[a-z]{2}/)?(?:a/)?content/)\\w+");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public String getAGBLink() {
        return "https://help.fc2.com/common/tos/en/";
    }

    @Override
    public void correctDownloadLink(final DownloadLink link) {
        final String fid = this.getFID(link);
        final boolean subContent = new Regex(link.getPluginPatternMatcher(), "/a/content/").matches();
        link.setPluginPatternMatcher("https://video.fc2.com/en" + (subContent ? "/a/content/" : "/content/") + fid);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    protected String getAccountNameSpaceLogin() {
        return "https://fc2.com/en/login.php?ref=video&switch_language=en";
    }

    @Override
    protected String getAccountNameSpacePremium() {
        return "https://" + this.getHost() + "/payment/fc2_premium/";
    }

    @Override
    protected String getAccountNameSpaceForLoginCheck() {
        return "https://" + this.getHost() + "/";
    }
}