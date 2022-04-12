//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
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
package jd.plugins.decrypter;

import java.util.ArrayList;

import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "amateurdumper.com" }, urls = { "https?://(?:www\\.)?amateurdumper\\.com/[^/]{10,}" })
public class AmateurDumperCom extends PornEmbedParser {
    public AmateurDumperCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    /* DEV NOTES */
    /* Porn_plugin */
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("(?i)>\\s*404 The page was not found") || br.getRequest().getHtmlCode().length() < 100) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (!this.canHandle(br.getURL())) {
            /* Redirect to external website */
            decryptedLinks.add(createDownloadlink(br.getURL()));
            return decryptedLinks;
        }
        String title = br.getRegex("<div class=\"video\\-hed hed3\">[\t\n\r ]+<h1>(.*?)</h1>").getMatch(0);
        if (title == null) {
            title = br.getRegex("<meta name=\"title\" content=\"(.*?)\" />").getMatch(0);
            if (title == null) {
                title = br.getRegex("<title>(?:Homemade Sex :: )?(.*?)( - Videos - Amateur Dumper)?</title>").getMatch(0);
            }
        }
        decryptedLinks.addAll(findEmbedUrls(title));
        return decryptedLinks;
    }
}