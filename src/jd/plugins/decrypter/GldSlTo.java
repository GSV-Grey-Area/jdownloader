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
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import org.jdownloader.captcha.v2.challenge.clickcaptcha.ClickedPoint;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;
import org.jdownloader.plugins.components.config.GldSlToConfig;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;

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

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class GldSlTo extends antiDDoSForDecrypt {
    public GldSlTo(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "goldesel.sx", "goldesel.to", "saugen.to", "laden.to", "blockbuster.to" });
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
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/[a-z0-9]+(/[a-z0-9\\-]+)?/\\d+.{4,}");
        }
        return ret.toArray(new String[0]);
    }

    private static final String HTML_CAPTCHA       = "Klicke in den gestrichelten Kreis, der sich somit von den anderen unterscheidet";
    private static final String HTML_LIMIT_REACHED = "class=\"captchaWait\"";

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        String parameter = param.toString();
        br.setFollowRedirects(true);
        getPage(parameter);
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        String fpName = br.getRegex(">Release\\s*:\\s*([^<>]+)<").getMatch(0);
        if (fpName == null) {
            fpName = br.getRegex("<title>\\s*([^<>\"]*?)\\s*</title>").getMatch(0);
        }
        if (fpName == null) {
            fpName = br.getRegex("<title>\\s*([^<>\"]*?)\\s*</title>").getMatch(0);
        }
        if (fpName == null) {
            fpName = new Regex(br.getURL(), "https?://[^/]+/.*/(.+)").getMatch(0);
        }
        fpName = Encoding.htmlDecode(fpName).trim();
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(fpName);
        final String[] decryptIDs = br.getRegex("data\\s*=\\s*\"([^<>\"]*?)\"").getColumn(0);
        if (decryptIDs.length == 0) {
            /* 2020-10-19: Some URLs only got P2P/usenet sources available! */
            logger.info("Failed to find any OCH mirrors");
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        final String[] hosterPrioList;
        String userHosterPrioListStr = PluginJsonConfig.get(GldSlToConfig.class).getHosterPriorityString();
        if (userHosterPrioListStr != null) {
            userHosterPrioListStr = userHosterPrioListStr.replace(" ", "");
            hosterPrioList = userHosterPrioListStr.split(",");
        } else {
            hosterPrioList = null;
        }
        final HashMap<String, List<String>> packages = new HashMap<String, List<String>>();
        for (final String decryptID : decryptIDs) {
            /* Structure of each ID: 1;123456;12345;12345;<hostername>;<season>;<episode> */
            final String[] idInfo = decryptID.split(";");
            final String hoster = idInfo[4];
            final String uniqueID = decryptID.replace(";" + hoster, "");
            if (packages.containsKey(uniqueID)) {
                packages.get(uniqueID).add(decryptID);
            } else {
                final List<String> newList = new ArrayList<String>();
                newList.add(decryptID);
                packages.put(uniqueID, newList);
            }
        }
        logger.info("Total number of decryptIDs: " + decryptIDs.length);
        final List<String> userAllowedDecryptIDs = new ArrayList<String>();
        /* Now decide which mirrors we actually want to crawl based on a user setting. */
        for (final Entry<String, List<String>> entry : packages.entrySet()) {
            final List<String> mirrorIDs = entry.getValue();
            if (mirrorIDs.size() == 1) {
                /* Only 1 host available -> Crawl that */
                userAllowedDecryptIDs.add(mirrorIDs.get(0));
            } else if (hosterPrioList != null) {
                /* Try to prefer user selected hosts/mirrors */
                boolean hasFoundPreferredHost = false;
                mirrorLoop: for (final String userPreferredHost : hosterPrioList) {
                    for (final String mirrorID : mirrorIDs) {
                        final String[] idInfo = mirrorID.split(";");
                        final String hoster = idInfo[4];
                        if (hoster.equalsIgnoreCase(userPreferredHost)) {
                            userAllowedDecryptIDs.add(mirrorID);
                            hasFoundPreferredHost = true;
                            break mirrorLoop;
                        }
                    }
                }
                if (!hasFoundPreferredHost) {
                    /* Fallback */
                    userAllowedDecryptIDs.addAll(mirrorIDs);
                }
            } else {
                /* Crawl all mirrors */
                userAllowedDecryptIDs.addAll(mirrorIDs);
            }
        }
        logger.info("Number of userAllowedDecryptIDs: " + userAllowedDecryptIDs.size());
        br.getHeaders().put("Accept", "*/*");
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        final int maxc = decryptIDs.length;
        int counter = 1;
        boolean captchafailed = false;
        for (final String decryptID : userAllowedDecryptIDs) {
            logger.info("Crawling item " + counter + " / " + decryptIDs.length + " | " + decryptID);
            // br.setCookie("goldesel.to", "__utma", "222304525.384242273.1432990594.1432990594.1433159390.2");
            // br.setCookie("goldesel.to", "__utmz", "222304525.1432990594.1.1.utmcsr=(direct)|utmccn=(direct)|utmcmd=(none)");
            // br.setCookie("goldesel.to", "__utmb", "222304525.1.10.1433159390");
            // br.setCookie("goldesel.to", "__utmc", "222304525");
            /* IMPORTANT */
            br.setCookie(this.br.getHost(), "__utmt", "1");
            postPage("/res/links", "data=" + Encoding.urlEncode(decryptID));
            if (br.containsHTML(HTML_CAPTCHA)) {
                for (int i = 1; i <= 3; i++) {
                    if (this.isAbort()) {
                        logger.info("Decryption aborted by user: " + parameter);
                        return decryptedLinks;
                    }
                    final String capLink = br.getRegex("\"(inc/cirlecaptcha\\.php[^<>\"]*?)\"").getMatch(0);
                    if (capLink == null) {
                        logger.warning("Decrypter broken for link: " + parameter);
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    final File file = this.getLocalCaptchaFile();
                    getCaptchaBrowser(br).getDownload(file, "/" + capLink);
                    String click_on;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        click_on = "Klicke in den gestrichelten Kreis!";
                    } else {
                        click_on = "Click in the dashed circle!";
                    }
                    logger.info("Click-Captcha | Mirror " + counter + " / " + maxc + " : " + decryptID);
                    final ClickedPoint cp = getCaptchaClickedPoint(br.getHost(), file, param, click_on);
                    if (cp == null) {
                        throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                    }
                    postPage("/res/links", "data=" + Encoding.urlEncode(decryptID) + "&xC=" + cp.getX() + "&yC=" + cp.getY());
                    if (br.containsHTML(HTML_LIMIT_REACHED)) {
                        logger.info("We have to wait because the user entered too many wrong captchas...");
                        int wait = 60;
                        String waittime = br.getRegex("<strong>(\\d+) Sekunden</strong> warten\\.").getMatch(0);
                        if (waittime != null) {
                            wait = Integer.parseInt(waittime);
                        } else {
                            logger.info("Did not find any short waittime --> Probably hourly limit is reached --> Stopping decryption");
                            break;
                        }
                        this.sleep(wait * 1001, param);
                        br.postPage("/res/links", "data=" + Encoding.urlEncode(decryptID));
                        continue;
                    }
                    if (br.containsHTML(HTML_CAPTCHA)) {
                        captchafailed = true;
                        continue;
                    }
                    captchafailed = false;
                    break;
                }
                if (captchafailed) {
                    logger.info("Captcha failed for decryptID: " + decryptID);
                    continue;
                }
            } else if (br.containsHTML("\"g\\-recaptcha\"")) {
                final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br).getToken();
                postPage("/res/links", "data=" + Encoding.urlEncode(decryptID) + "&rcc=" + Encoding.urlEncode(recaptchaV2Response));
            }
            if (br.containsHTML(HTML_LIMIT_REACHED)) {
                logger.info("Probably hourly limit is reached --> Stopping decryption");
                return decryptedLinks;
            }
            final String[] finallinks = br.getRegex("url\\s*=\\s*\"(https?[^<>\"]*?)\"").getColumn(0);
            for (final String finallink : finallinks) {
                final DownloadLink dl = createDownloadlink(Encoding.htmlDecode(finallink));
                fp.add(dl);
                distribute(dl);
                decryptedLinks.add(dl);
            }
            counter++;
            if (this.isAbort()) {
                break;
            }
        }
        /* Only 1 link + wrong captcha --> */
        if (decryptedLinks.size() == 0 && captchafailed) {
            throw new PluginException(LinkStatus.ERROR_CAPTCHA);
        }
        if (decryptedLinks.size() == 0) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        fp.addLinks(decryptedLinks);
        return decryptedLinks;
    }

    /* Prevent confusion */
    public int getMaxConcurrentProcessingInstances() {
        return 1;
    }

    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return true;
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return GldSlToConfig.class;
    }
}