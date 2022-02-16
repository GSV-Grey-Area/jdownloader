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

import java.io.File;
import java.util.ArrayList;

import org.appwork.utils.IO;
import org.appwork.utils.Regex;
import org.jdownloader.captcha.v2.challenge.clickcaptcha.ClickedPoint;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.PluginJSonUtils;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "lockmy.link" }, urls = { "https?://(?:www\\.)?lockmy\\.link/l/([A-Za-z0-9]+)/?" })
public class LockmyLink extends PluginForDecrypt {
    public LockmyLink(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, final ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String shortID = new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0);
        br.getHeaders().put("Origin", "https://" + this.getHost());
        br.getHeaders().put("Accept", "*/*");
        br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded");
        br.getHeaders().put("sec-ch-ua", "\"Chromium\";v=\"94\", \"Google Chrome\";v=\"94\", \";Not A Brand\";v=\"99\"");
        br.getHeaders().put("sec-ch-ua-mobile", "?0");
        br.getHeaders().put("sec-ch-ua-platform", "\"Windows\"");
        /* Important! Without this header, this is all we'll get: "<p>ERROR! Please reload the page</p>" */
        br.getHeaders().put("sec-fetch-site", "same-origin");
        br.getHeaders().put("sec-fetch-mode", "cors");
        br.getHeaders().put("sec-fetch-dest", "empty");
        br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!this.canHandle(br.getURL())) {
            /* E.g. redirect to "/404" */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Browser brc = this.br.cloneBrowser();
        brc.postPage("/api/ajax.php", "url=[\"" + param.getCryptedUrl() + "\"]");
        /* "Workaround" for json response */
        brc.getRequest().setHtmlCode(PluginJSonUtils.unescape(brc.getRequest().getHtmlCode()));
        final String captchaImageBase64 = brc.getRegex("data:image/png;base64,([a-zA-Z0-9_/\\+\\=]+)").getMatch(0);
        // final String captchaurl = brc.getRegex("(/api/image\\.php\\?id=[A-Za-z0-9]+)").getMatch(0);
        if (captchaImageBase64 == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        byte[] image_bytes = org.appwork.utils.encoding.Base64.decode(captchaImageBase64);
        if (image_bytes == null || image_bytes.length == 0) {
            image_bytes = org.appwork.utils.encoding.Base64.decodeFast(captchaImageBase64);
        }
        final File captchaImage = getLocalCaptchaFile(".png");
        IO.writeToFile(captchaImage, image_bytes);
        String captchaDescr = brc.getRegex("<p class=\"title\">([^<]*)</p>").getMatch(0);
        if (captchaDescr == null) {
            /* 2022-02-16 */
            captchaDescr = br.getRegex("<p>([^<>\"]+)</p></div></div>").getMatch(0);
        }
        if (captchaDescr == null) {
            /* Fallback */
            captchaDescr = "Click on the lock";
        }
        final ClickedPoint cp = getCaptchaClickedPoint(captchaImage, param, captchaDescr);
        br.postPage("/api/ajax.php", "shortId=" + shortID + "&coords=" + cp.getX() + ".5-" + cp.getY());
        /* "Workaround" for json response */
        br.getRequest().setHtmlCode(PluginJSonUtils.unescape(br.getRequest().getHtmlCode()));
        if (br.containsHTML("(?i)class=\"title\">\\s*ERROR")) {
            /*
             * 2021-10-20: html may also contain: "<p>Link not found</p>" --> This is wrong! This response will only happen if the
             * captcha-answer is wrong!
             */
            throw new PluginException(LinkStatus.ERROR_CAPTCHA);
        }
        final String[] results = br.getRegex("target=\"_blank\" href=\"(https?[^\"]+)").getColumn(0);
        if (results.length == 0) {
            logger.warning("Failed to find any results -> Offline, wrong captcha or plugin broken");
        } else {
            for (final String result : results) {
                decryptedLinks.add(createDownloadlink(result));
            }
        }
        return decryptedLinks;
    }
}
