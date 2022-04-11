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
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "abload.de" }, urls = { "https?://(?:www\\.)?abload\\.de/(gallery\\.php\\?key=[A-Za-z0-9]+|browseGallery\\.php\\?gal=[A-Za-z0-9]+\\&img=.+|image.php\\?img=[\\w\\.]+)" })
public class AbloadDeGallery extends PluginForDecrypt {
    public AbloadDeGallery(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.IMAGE_GALLERY };
    }

    private static final String DIRECTLINKREGEX  = "um die Originalgröße anzuzeigen\\.</div><img src=\"(/img/[^\"]+)\"";
    private static final String DIRECTLINKREGEX2 = "(/img/[^\"]+)\"";

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        String parameter = param.toString();
        br.setFollowRedirects(true);
        br.getPage(parameter);
        if (br.containsHTML("Ein Bild mit diesem Dateinamen existiert nicht\\.") || br.containsHTML(">Dieses Bild wurde gelöscht")) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        } else if (br.containsHTML("Galerie nicht gefunden\\.") || br.containsHTML("Gallery not found\\.")) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        if (!parameter.contains("browseGallery.php?gal=") && !parameter.contains("image.php")) {
            final String galID = new Regex(parameter, "([A-Za-z0-9]+)$").getMatch(0);
            // Needed for galleries with ajax-picture-reloac function
            String[] links = br.getRegex("\"filename\":\"([^<>\"]+)\"").getColumn(0);
            // For "normal" galleries
            if (links == null || links.length == 0) {
                links = br.getRegex("\"/browseGallery\\.php\\?gal=[A-Za-z0-9]+\\&amp;img=([^<>\"/]*?)\"").getColumn(0);
            }
            if (links == null || links.length == 0 || galID == null) {
                logger.warning("Decrypter broken for link: " + parameter);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String fpName = br.getRegex("<title>Galerie:([^<>\"]*?)\\- abload\\.de</title>").getMatch(0);
            if (fpName == null) {
                /* Fallback */
                fpName = galID;
            }
            fpName = Encoding.htmlDecode(fpName.trim());
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(fpName);
            for (String singlePictureLink : links) {
                // singlePictureLink = "https://www.abload.de/browseGallery.php?gal=" + galID + "&img=" +
                // Encoding.htmlDecode(singlePictureLink);
                // br.getPage(singlePictureLink);
                // String finallink = br.getRegex(DIRECTLINKREGEX).getMatch(0);
                // if (finallink == null) {
                // finallink = br.getRegex(DIRECTLINKREGEX2).getMatch(0);
                // }
                // if (finallink == null) {
                // throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                // }
                final String finallink = "https://abload.de/img/" + singlePictureLink;
                final DownloadLink dl = this.createDownloadlink(finallink);
                dl.setFinalFileName(singlePictureLink);
                dl.setAvailable(true);
                dl._setFilePackage(fp);
                decryptedLinks.add(dl);
            }
        } else {
            String finallink = br.getRegex(DIRECTLINKREGEX).getMatch(0);
            if (finallink == null) {
                finallink = br.getRegex(DIRECTLINKREGEX2).getMatch(0);
            }
            if (finallink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            decryptedLinks.add(createDownloadlink("directhttp://" + br.getURL(finallink)));
        }
        return decryptedLinks;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}