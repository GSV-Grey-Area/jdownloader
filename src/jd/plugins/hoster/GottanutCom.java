//jDownloader - Downloadmanager
//Copyright (C) 2020  JD-Team support@jdownloader.org
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
import jd.plugins.Account;
import jd.plugins.HostPlugin;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class GottanutCom extends KernelVideoSharingComV2 {
    public GottanutCom(final PluginWrapper wrapper) {
        super(wrapper);
    }

    /** Add all KVS hosts to this list that fit the main template without the need of ANY changes to this class. */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "gottanut.com" });
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(video/\\d+/[a-z0-9\\-]+|embed/\\d+/?)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    protected int getMaxChunks(final Account account) {
        /* 2020-11-06: 2 chunks are working fine but we'll limit it to 1 anyways to avoid issues. */
        return 1;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    @Override
    protected boolean enableFastLinkcheck() {
        /*
         * 2020-11-06: Enable this because of the user is already downloading, fileservers will return error 503 and we cannot get filesizes
         * during linkcheck anyways!
         */
        return true;
    }

    @Override
    String generateContentURL(final String host, final String fuid, final String urlSlug) {
        return this.getProtocol() + "www." + host + "/video/" + fuid + "/" + urlSlug;
    }
}