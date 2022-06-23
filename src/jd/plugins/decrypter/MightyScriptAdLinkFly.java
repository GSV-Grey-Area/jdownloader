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
import java.util.regex.Pattern;

import org.appwork.utils.StringUtils;
import org.jdownloader.captcha.v2.challenge.hcaptcha.CaptchaHelperCrawlerPluginHCaptcha;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.Form.MethodType;
import jd.parser.html.InputField;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.components.SiteType.SiteTemplate;

/**
 *
 * @author raztoki
 * @tags: similar to OuoIo
 * @examples: Without captcha: met.bz <br />
 *            With reCaptchaV2 (like most): sh2rt.com <br />
 *            With solvemedia: clik.pw
 *
 */
@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public abstract class MightyScriptAdLinkFly extends antiDDoSForDecrypt {
    public enum CaptchaType {
        hCaptcha,
        reCaptchaV2,
        reCaptchaV2_invisible,
        solvemedia,
        WTF
    };

    public MightyScriptAdLinkFly(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public int getMaxConcurrentProcessingInstances() {
        return 1;
    }

    private String appVars = null;

    /** Use this to correct URL added by user if necessary. */
    protected void correctURL(final CryptedLink param) {
    }

    protected boolean supportsHost(final String host) {
        return host.equalsIgnoreCase(this.getHost());
    }

    protected String regexAppVars(final Browser br) {
        return br.getRegex("var (app_vars.*?)</script>").getMatch(0);
    }

    protected Form getContinueForm(CryptedLink param, final Browser br) {
        /* 2018-07-18: It is very important to keep this exact as some websites have "ad-forms" e.g. urlcloud.us !! */
        Form f2 = br.getFormbyKey("_Token[fields]");
        if (f2 == null) {
            f2 = br.getFormbyKey("_Token%5Bfields%5D");
        }
        if (f2 == null) {
            f2 = getLinksGoForm(param, br);
        }
        if (f2 == null) {
            f2 = br.getForm(0);
        }
        return f2;
    }

    protected Form getLinksGoForm(CryptedLink param, final Browser br) {
        return br.getFormbyAction("/links/go");
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String sourceHost = Browser.getHost(param.getCryptedUrl());
        correctURL(param);
        ret.addAll(handlePreCrawlProcess(param));
        if (!ret.isEmpty()) {
            /* E.g. direct redirect */
            return ret;
        } else if (br.getHttpConnection().getResponseCode() == 403 || br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.toString().length() < 100) {
            /* 2020-05-29: E.g. https://uii.io/full */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        br.setFollowRedirects(true);
        final Form beforeCaptcha = br.getFormbyProperty("id", "before-captcha");
        if (beforeCaptcha != null) {
            /* 2019-10-30: E.g. exe.io */
            logger.info("Found pre-captcha Form");
            this.submitForm(beforeCaptcha);
        }
        appVars = regexAppVars(this.br);
        Form form = getCaptchaForm(br);
        if (form == null) {
            if (ret.size() > 0) {
                return ret;
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        // form.remove("_Token%5Bunlocked%5D");
        // form.put("_Token%5Bunlocked%5D", "adcopy_challenge%7Cadcopy_response%7Ccoinhive-captcha-token%7Cg-recaptcha-response");
        // final InputField ifield = form.getInputField("_Token%5Bfields%5D");
        // if (ifield != null) {
        // final String value = ifield.getValue();
        // final String valueNew = value.replace("%253Aref", "%3Aref");
        // form.remove("_Token%5Bfields%5D");
        // form.put("_Token%5Bfields%5D", valueNew);
        // }
        String recaptchaV2Response = null;
        /* 2018-07-18: Not all sites require a captcha to be solved */
        CaptchaType captchaType = getCaptchaType();
        if (evalulateCaptcha(captchaType, param.getCryptedUrl())) {
            logger.info("Captcha required");
            boolean requiresCaptchaWhichCanFail = false;
            boolean captchaFailed = false;
            for (int i = 0; i <= 2; i++) {
                form = getCaptchaForm(br);
                if (form == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final InputField action = form.getInputField("action");
                if (action == null || !"continue".equals(action.getValue()) || "captcha".equals(action.getValue())) {
                    /* Captcha type will usually stay the same even on bad solve attempts! */
                    // captchaType = getCaptchaType();
                    if (captchaType == CaptchaType.hCaptcha) {
                        requiresCaptchaWhichCanFail = false;
                        final String key = getAppVarsResult("hcaptcha_checkbox_site_key");
                        if (StringUtils.isEmpty(key)) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Failed to find reCaptchaV2 key");
                        }
                        final String siteURL;
                        if (this.getSpecialReferer() != null) {
                            /* Required e.g. for sh2rt.com. */
                            siteURL = br.getBaseURL();
                        } else {
                            /* Fine for most of all websites. */
                            siteURL = br.getURL();
                        }
                        final String hCaptchaResponse = new CaptchaHelperCrawlerPluginHCaptcha(this, br, key) {
                            @Override
                            protected String getSiteUrl() {
                                return siteURL;
                            }
                        }.getToken();
                        form.put("g-recaptcha-response", Encoding.urlEncode(hCaptchaResponse));
                        form.put("h-captcha-response", Encoding.urlEncode(hCaptchaResponse));
                    } else if (captchaType == CaptchaType.reCaptchaV2 || captchaType == CaptchaType.reCaptchaV2_invisible) {
                        requiresCaptchaWhichCanFail = false;
                        final String key;
                        if (captchaType == CaptchaType.reCaptchaV2) {
                            key = getAppVarsResult("reCAPTCHA_site_key");
                        } else {
                            key = getAppVarsResult("invisible_reCAPTCHA_site_key");
                        }
                        if (StringUtils.isEmpty(key)) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Failed to find reCaptchaV2 key");
                        }
                        /**
                         * Some websites do not allow users to access the target URL directly but will require a certain Referer to be set.
                         * </br>
                         * We pre-set this in our browser but if that same URL is opened in browser, it may redirect to another website as
                         * the Referer is missing. In this case we'll use the main page to solve the captcha to prevent this from happening.
                         */
                        final String reCaptchaSiteURL;
                        if (this.getSpecialReferer() != null) {
                            /* Required e.g. for sh2rt.com. */
                            reCaptchaSiteURL = br.getBaseURL();
                        } else {
                            /* Fine for most of all websites. */
                            reCaptchaSiteURL = br.getURL();
                        }
                        final boolean isInvisible = captchaType == CaptchaType.reCaptchaV2_invisible;
                        recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br, key) {
                            @Override
                            public TYPE getType() {
                                if (isInvisible) {
                                    return TYPE.INVISIBLE;
                                } else {
                                    return TYPE.NORMAL;
                                }
                            }

                            @Override
                            protected String getSiteUrl() {
                                return reCaptchaSiteURL;
                            }
                        }.getToken();
                        form.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                    } else if (captchaType == CaptchaType.solvemedia) {
                        final String solvemediaChallengeKey = this.getAppVarsResult("solvemedia_challenge_key");
                        if (StringUtils.isEmpty(solvemediaChallengeKey)) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Failed to find solvemedia_challenge_key");
                        }
                        requiresCaptchaWhichCanFail = true;
                        final org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia sm = new org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia(br);
                        sm.setChallengeKey(solvemediaChallengeKey);
                        final String code = getCaptchaCode("solvemedia", sm.downloadCaptcha(getLocalCaptchaFile()), param);
                        final String chid = sm.getChallenge(code);
                        form.put("adcopy_challenge", chid);
                        form.put("adcopy_response", "manual_challenge");
                    } else if (captchaType != null) {
                        /* This should never happen */
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unsupported captcha type:" + captchaType);
                    } else {
                        // * captchas(site) -> ads page -> no captcha(site)
                        logger.info("no captcha?!");
                    }
                }
                submitForm(form);
                // refresh appVars!
                appVars = regexAppVars(this.br);
                captchaType = getCaptchaType();
                if (getLinksGoForm(param, br) != null) {
                    captchaFailed = false;
                    break;
                } else if (requiresCaptchaWhichCanFail && this.br.containsHTML("(?i)The CAPTCHA was incorrect")) {
                    captchaFailed = true;
                    continue;
                } else {
                    // another captcha round? iir.ai
                    continue;
                }
            }
            if (requiresCaptchaWhichCanFail && captchaFailed) {
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
        } else if (form != null) {
            /* 2019-01-30: E.g. "safelinku.com", "idsly.bid", "idsly.net" */
            logger.info("No captcha required");
            /*
             * 2020-04-01: This would lead to a failure. I was not able to reproduce this with safelinku.com anymore - it would sometimes
             * submit this form which == f2 --> Failure e.g. za.gl
             */
            // this.submitForm(form);
        }
        hookAfterCaptcha(this.br);
        final boolean skipWait = waittimeIsSkippable(sourceHost);
        /** TODO: Fix waittime-detection for tmearn.com */
        Form f2 = getContinueForm(param, br);
        if (f2 != null) {
            br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
            br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            br.getHeaders().put("Origin", "https://" + br.getHost());
            if (br.containsHTML(org.appwork.utils.Regex.escape("name:'gc_token', value:e}).appendTo('#go-link')")) && recaptchaV2Response != null) {
                /* 2021-08-06: Special: tny.so (and maybe others too) */
                f2.put("gc_token", Encoding.urlEncode(recaptchaV2Response));
            }
            if (f2.hasInputFieldByName("_csrfToken")) {
                /*
                 * 2021-03-29: E.g. tny.so will return error if this header is missing/wrong:
                 * {"message":"CSRF token mismatch.","url":"\/links\/go","code":403}
                 */
                final String csrftoken = f2.getInputField("_csrfToken").getValue();
                if (!StringUtils.isEmpty(csrftoken)) {
                    br.getHeaders().put("X-CSRF-Token", csrftoken);
                } else {
                    logger.warning("csrftoken fielld exists but no value present");
                }
            }
            String waitStr = br.getRegex("\"counter_value\"\\s*:\\s*\"(\\d+)\"").getMatch(0);
            if (waitStr == null) {
                waitStr = br.getRegex(">\\s*Please Wait\\s*(\\d+)s\\s*<").getMatch(0);
                if (waitStr == null) {
                    /* 2018-12-12: E.g. rawabbet.com[RIP 2019-02-21] */
                    waitStr = br.getRegex("class=\"timer\">\\s*?(\\d+)\\s*?<").getMatch(0);
                }
                if (waitStr == null) {
                    /* 2021-01-12: E.g. bestcash2020.com */
                    waitStr = br.getRegex("id=\"timer\"[^>]+>\\s*?(\\d+)\\s*?<").getMatch(0);
                }
            }
            if (!skipWait) {
                if (waitStr != null) {
                    logger.info("Found waittime in html, waiting seconds: " + waitStr);
                    final int wait = Integer.parseInt(waitStr) * +1;
                    this.sleep(wait * 1000, param);
                } else {
                    logger.info("Failed to find waittime in html");
                }
            } else {
                if (waitStr != null) {
                    logger.info("Skipping waittime (seconds): " + waitStr);
                } else {
                    logger.info("Skipping waittime");
                }
            }
            submitForm(f2);
        }
        final String finallink = getFinallink();
        if (finallink == null) {
            if (br.containsHTML("(?i)<h1>Whoops, looks like something went wrong\\.</h1>")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        ret.add(createDownloadlink(finallink));
        return ret;
    }

    /** Override to do something after the captcha (also gets called when no captcha was needed). */
    protected void hookAfterCaptcha(final Browser br) throws Exception {
    }

    /** Accesses input URL and handles "Pre-AdLinkFly" redirects. */
    protected ArrayList<DownloadLink> handlePreCrawlProcess(final CryptedLink param) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        if (getSpecialReferer() != null) {
            br.getHeaders().put("Referer", getSpecialReferer());
            /* Do not expect direct redirects to our target-URL. */
            br.setFollowRedirects(true);
        } else {
            br.setFollowRedirects(false);
        }
        getPage(param.getCryptedUrl());
        // 2019-11-13: http->https->different domain(https)
        // 2019-11-13: http->https->different domain(http)->different domain(https)
        String firstRedirect = null;
        int count = 1;
        while (true) {
            if (isAbort()) {
                throw new InterruptedException();
            }
            String redirect = br.getRedirectLocation();
            if (redirect == null) {
                /* 2019-01-29: E.g. cuon.io */
                redirect = br.getRegex("<meta http\\-equiv=\"refresh\" content=\"\\d+;url=(https?://[^\"]+)\">").getMatch(0);
            }
            if (redirect == null) {
                /* 2019-04-25: E.g. clkfly.pw */
                redirect = br.getHttpConnection().getHeaderField("Refresh");
                if (redirect != null && redirect.matches("^\\d+, http.+")) {
                    redirect = new Regex(redirect, "^\\d+, (http.+)").getMatch(0);
                }
            }
            if (redirect == null) {
                /* 2021-01-14 exe.io */
                /* document.getElementById('clickable').click() */
                redirect = br.containsHTML("\\('clickable'\\)\\.click\\(\\)") ? br.getRegex("<a\\s*href\\s*=\\s*\"(https?://[^\"]+)\"\\s*id\\s*=\\s*\"clickable\"").getMatch(0) : null;
            }
            if (redirect == null) {
                break;
            }
            if (!this.supportsHost(Browser.getHost(redirect))) {
                /*
                 * 2018-07-18: Direct redirect without captcha or any Form e.g. vivads.net OR redirect to other domain of same service e.g.
                 * wi.cr --> wicr.me
                 */
                firstRedirect = redirect;
                break;
            } else {
                getPage(redirect);
                count++;
                if (count > 10) {
                    throw new DecrypterException("Too many redirects!");
                }
            }
        }
        if (firstRedirect != null) {
            /**
             * Check if this is redirect redirect or if it really is the one we expect. </br>
             * Some websites redirect e.g. to a fake blog and only redirect back to the usual handling if you re-access the main URL with
             * that fake blog as referer header e.g.: adshort.co, ez4short.com </br>
             * In some cases this special referer is pre-given via getSpecialReferer in which we do not have to re-check.
             */
            if (getSpecialReferer() != null) {
                /* Assume that redirect redirects to external website and use it as our final result. */
                ret.add(this.createDownloadlink(firstRedirect));
            } else {
                logger.info("Checking if redirect is external redirect or redirect to fake blog");
                br.getHeaders().put("Referer", firstRedirect);
                br.setFollowRedirects(false);
                getPage(param.getCryptedUrl());
                final String secondRedirect = br.getRedirectLocation();
                if (secondRedirect != null) {
                    if (!StringUtils.equalsIgnoreCase(firstRedirect, secondRedirect)) {
                        logger.warning("Got different redirect on 2nd attempt: First: " + firstRedirect + " | Second: " + secondRedirect);
                        ret.add(this.createDownloadlink(firstRedirect));
                        ret.add(this.createDownloadlink(secondRedirect));
                    } else {
                        logger.info("Same redirect happens even with Referer --> Returning final result: " + secondRedirect);
                        ret.add(this.createDownloadlink(firstRedirect));
                    }
                } else if (regexAppVars(this.br) == null) {
                    logger.warning("Result looks like plugin failure");
                }
            }
        }
        return ret;
    }

    protected boolean waittimeIsSkippable(final String source_host) {
        return false;
    }

    protected boolean captchaIsSkippable(final String source_host) {
        return false;
    }

    protected Form getCaptchaForm(final Browser br) {
        final Form[] forms = br.getForms();
        for (Form form : forms) {
            if (MethodType.POST.equals(form.getMethod())) {
                return form;
            }
        }
        return br.getForm(0);
    }

    /**
     * true = captcha required, false = no captcha required <br />
     *
     * @param captchatype
     *            : Type of the captcha found in js/html - required in some rare cases.
     */
    private boolean evalulateCaptcha(final CaptchaType captchatype, final String url_source) {
        // if ("yes" !== app_vars.enable_captcha) return !0;
        final String source_host = Browser.getHost(url_source);
        boolean hasCaptcha;
        // String captchaIndicatorValue = getAppVarsResult("enable_captcha");
        final String captchaIndicator = getAppVarsResult("captcha_shortlink");
        if (captchaIndicator != null) {
            /* Most website will contain this boolean-like value telling us whether we need to solve a captcha or not. */
            logger.info("Found captchaIndicator");
            if ("yes".equals(captchaIndicator)) {
                logger.info("Negative captchaIndicator --> No captcha required(?)");
                hasCaptcha = true;
            } else {
                logger.info("Positive captchaIndicator --> Captcha required(?)");
                hasCaptcha = false;
            }
        } else {
            /*
             * In some cases, we have to check for the type of the captcha to find out whether there is a captcha or not (unsafe method,
             * only needed in rare cases, see example websites in header of this class!)
             */
            if (captchatype != null) {
                hasCaptcha = true;
            } else {
                hasCaptcha = false;
            }
        }
        if (hasCaptcha && this.captchaIsSkippable(source_host)) {
            logger.info("Captcha should be required but current host does not require captcha");
            hasCaptcha = false;
        }
        return hasCaptcha;
    }

    private CaptchaType getCaptchaType() {
        final String captchaTypeStr = getAppVarsResult("captcha_type");
        if (StringUtils.isEmpty(captchaTypeStr)) {
            /* 2018-12-11: Special case e.g. linkdrop.net */
            final String reCaptchaV2Key = getAppVarsResult("reCAPTCHA_site_key");
            if (reCaptchaV2Key != null) {
                return CaptchaType.reCaptchaV2;
            }
            /* No captcha or plugin broken */
            return null;
        }
        if (captchaTypeStr.equalsIgnoreCase("hcaptcha_checkbox")) {
            return CaptchaType.hCaptcha;
        } else if (captchaTypeStr.equalsIgnoreCase("recaptcha")) {
            /*
             * 2018-07-18: For 'recaptcha', key is in "reCAPTCHA_site_key"; for 'invisible-recaptcha', key is in
             * "invisible_reCAPTCHA_site_key" --> We can usually use "reCAPTCHA_site_key" as well (tested with urle.co) ... but it is better
             * to use the correct one instead - especially because sometimes only that one is available (e.g. kingurl.net).
             */
            return CaptchaType.reCaptchaV2;
        } else if (captchaTypeStr.equalsIgnoreCase("invisible-recaptcha")) {
            return CaptchaType.reCaptchaV2_invisible;
        } else if (captchaTypeStr.equalsIgnoreCase("solvemedia")) {
            return CaptchaType.solvemedia;
        } else {
            return CaptchaType.WTF;
        }
    }

    // private boolean evalulateRecaptchaV2(final Form form) {
    // final String captchaBtn = form.getRegex("<div [^>]*id=\"captchaShortlink\"[^>]*>").getMatch(-1);
    // if (captchaBtn != null) {
    // /*
    // * "recaptcha" === app_vars.captcha_type && ("" === app_vars.user_id && "1" === app_vars.captcha_short_anonymous &&
    // * $("#captchaShort").length && ($("#shorten .btn-captcha").attr("disabled", "disabled"), captchaShort =
    // * grecaptcha.render("captchaShort", {
    // */
    // /*
    // * yes" === app_vars.captcha_shortlink && $("#captchaShortlink").length && ($("#link-view
    // * .btn-captcha").attr("disabled", "disabled"), captchaShortlink = grecaptcha.render("captchaShortlink", {
    // */
    // final String captchaType = getAppVarsResult("captcha_type");
    // final String userId = getAppVarsResult("user_id");
    // if ("recaptcha".equals(captchaType) && "".equals(userId)) {
    // return true;
    // }
    // }
    // // fail over, some seem to be using this
    // if (form.containsHTML("(?:id|class)=(\"|')g-recaptcha\\1")) {
    // return true;
    // } else if (form.containsHTML("invisibleCaptchaShortlink")) {
    // /* 'Invisible' reCaptchaV2 */
    // return true;
    // }
    // return false;
    // }
    //
    // private boolean evalulateSolvemedia(final Form form) {
    // final String solvemediaChallengeKey =
    // br.getRegex("app_vars\\[\\'solvemedia_challenge_key\\'\\]\\s*?=\\s*?\\'([^<>\"\\']+)\\';").getMatch(0);
    // if (form.containsHTML("adcopy_response") && solvemediaChallengeKey != null) {
    // return true;
    // }
    // return false;
    // }
    private String getAppVarsResult(final String input) {
        String result = new Regex(this.appVars, "app_vars\\['" + Pattern.quote(input) + "'\\]\\s*=\\s*'([^']*)'").getMatch(0);
        if (result == null) {
            /* 2018-07-18: json e.g. adbilty.me */
            result = PluginJSonUtils.getJson(this.appVars, input);
        }
        return result;
    }

    private String getFinallink() throws Exception {
        /* For >90%, this json-attempt should work! */
        String finallink = PluginJSonUtils.getJsonValue(br, "url");
        if (inValidate(finallink) || !finallink.startsWith("http")) {
            finallink = br.getRegex("<a href=(\"|')(.*?)\\1[^>]+>\\s*Get\\s*Link\\s*</a>").getMatch(1);
            if (inValidate(finallink)) {
                finallink = br.getRegex("<a[^>]*href=(\"|')(.*?)\\1[^>]*>Continue[^<]*</a>").getMatch(1);
            }
        }
        /* 2020-02-03: clk.in: p.clk.in/?n=bla */
        if (!StringUtils.isEmpty(finallink) && finallink.matches("https?://p\\.[^/]+/\\?n=.+")) {
            logger.info("Special case: Finallink seems to lead to another step: " + finallink);
            this.getPage(finallink);
            /* 2020-110-6: E.g. clicksfly.com */
            finallink = br.getRegex("<div class=\"button\">\\s*<center>\\s*<a name=\"a\"[^>]*href=\"(http[^\"]+)\">").getMatch(0);
        }
        return finallink;
    }

    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        /* Most of all providers will require the user to solve a reCaptchaV2. */
        return true;
    }

    /** Use this to define a Referer to be used for the first request. */
    protected String getSpecialReferer() {
        return null;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.MightyScript_AdLinkFly;
    }
}