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
import java.util.List;
import java.util.Map;

import org.appwork.utils.parser.UrlQuery;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.requests.GetRequest;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.CopyCaseCom;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { CopyCaseCom.class })
public class CopyCaseComFolder extends PluginForDecrypt {
    public CopyCaseComFolder(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        return CopyCaseCom.getPluginDomains();
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/folder/(([A-Za-z0-9]+)(/folder/[A-Za-z0-9]+)?)");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        final CopyCaseCom hosterplugin = (CopyCaseCom) this.getNewPluginForHostInstance(this.getHost());
        if (account != null) {
            hosterplugin.login(br, account, false);
        }
        final String folderPathURL = new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0);
        final UrlQuery query = new UrlQuery();
        String passCode = param.getDecrypterPassword();
        FilePackage fp = null;
        String path = null;
        int page = 1;
        do {
            /* TODO: Add pagination support */
            final GetRequest req = new GetRequest(hosterplugin.getAPIBase() + "/file-folders/" + folderPathURL + "?" + query.toString());
            final Map<String, Object> resp = hosterplugin.callAPI(br, null, account, req, false);
            final Map<String, Object> data = (Map<String, Object>) resp.get("data");
            final Map<String, Object> pagination = (Map<String, Object>) resp.get("pagination");
            if (fp == null) {
                final String currentFolderName = data.get("name").toString();
                path = currentFolderName;
                final List<Map<String, Object>> breadcrumbs = (List<Map<String, Object>>) data.get("breadcrumbs");
                if (breadcrumbs != null && breadcrumbs.size() > 0) {
                    for (final Map<String, Object> breadcrumb : breadcrumbs) {
                        path = breadcrumb.get("name") + "/" + path;
                    }
                }
                fp = FilePackage.getInstance();
                fp.setName(path);
                logger.info("Crawling folder: " + currentFolderName + " | Full path: " + path);
            }
            final List<Map<String, Object>> files = (List<Map<String, Object>>) resp.get("files");
            final List<Map<String, Object>> subfolders = (List<Map<String, Object>>) resp.get("subfolders");
            if ((files == null || files.isEmpty()) && (subfolders == null || subfolders.isEmpty())) {
                throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER, "EMPTY_FOLDER_" + path);
            }
            for (final Map<String, Object> file : files) {
                final DownloadLink link = this.createDownloadlink("https://" + this.getHost() + "/file/" + file.get("key") + "/" + file.get("slug"));
                link.setFinalFileName(file.get("name").toString());
                link.setVerifiedFileSize(((Number) file.get("size")).longValue());
                link.setAvailable(true);
                link.setRelativeDownloadFolderPath(path);
                if (passCode != null) {
                    /*
                     * Download password can be different from folder password or even none but let's set this password here so if needed,
                     * it will at least be tried first.
                     */
                    link.setDownloadPassword(passCode);
                }
                link._setFilePackage(fp);
                ret.add(link);
                distribute(link);
            }
            for (final Map<String, Object> subfolder : subfolders) {
                // final int total_files = ((Number) subfolder.get("total_files")).intValue();
                final DownloadLink link = this.createDownloadlink("https://" + this.getHost() + "/folder/" + subfolder.get("folder_share_key") + "/folder/" + subfolder.get("key"));
                if (passCode != null) {
                    link.setDownloadPassword(passCode);
                }
                ret.add(link);
                distribute(link);
            }
            final int pageMax = ((Number) pagination.get("total")).intValue();
            logger.info("Crawlede page " + page + "/" + pageMax + " | Found items so far: " + ret.size());
            if (page >= pageMax) {
                logger.info("Stopping because: Reached last page: " + page);
                break;
            }
            page++;
            // TODO: Add pagination support
            logger.info("Stopping because: Pagination hasn't been implemented yet");
            break;
        } while (true);
        return ret;
    }
}
