# Terms of Service

**MindfulHome**
**Effective Date:** March 24, 2026
**Last Updated:** March 24, 2026

---

## 1. Acceptance of Terms

By installing or using MindfulHome ("the App"), you agree to these Terms of Service ("Terms"). If you do not agree, do not use the App.

---

## 2. What MindfulHome Does

MindfulHome is an Android home launcher designed to encourage intentional phone use. It does this by:

- Letting you set voluntary time limits when opening apps
- Tracking whether you stay within those limits (karma system)
- Gradually de-emphasizing apps that consistently overrun their timers
- Providing an AI-assisted conversation before accessing hidden apps
- Offering a to-do companion widget

The App is designed around soft nudges, not hard blocks. **You remain in full control of your device at all times.** Nothing is force-closed, and all restrictions can be bypassed.

---

## 3. Eligibility

You must be at least 13 years old to use the App (or the minimum age required by applicable law in your jurisdiction). By using the App, you represent that you meet this requirement.

---

## 4. Data We Collect and How We Use It

### 4.1 Data Stored Locally on Your Device

The following data is stored only on your device using an encrypted local database:

| Data | Purpose |
|------|---------|
| App usage sessions (timestamps, timer durations, overruns) | Calculating karma scores |
| Per-app karma scores and open counts | Determining home screen visibility |
| Session event logs | Providing usage insights |
| To-do items (text, deadlines, priority) | Todo companion widget |
| Home screen layout and folder configuration | Personalizing your launcher |

This data never leaves your device unless you explicitly enable optional cloud features (see §4.3).

### 4.2 Android Permissions

The App requests these system permissions and uses them solely as described:

| Permission | Use |
|-----------|-----|
| `QUERY_ALL_PACKAGES` | Listing installed apps for the home screen |
| `PACKAGE_USAGE_STATS` | Detecting the foreground app to track timer compliance |
| `FOREGROUND_SERVICE` | Running the countdown timer reliably |
| `POST_NOTIFICATIONS` | Sending timer nudge alerts |
| `RECEIVE_BOOT_COMPLETED` | Restoring the launcher after a device restart |
| `SYSTEM_ALERT_WINDOW` | Displaying nudge overlays when a timer expires |

### 4.3 Optional Account and Cloud Features

Creating an account and enabling cloud features is entirely optional. If you sign in with Google:

- **Google Sign-In:** We receive a one-time ID token from Google, which we exchange for an app authentication token. We store your email address and the app token in encrypted preferences on your device. We do not store your Google ID token.
- **AI Gatekeeper (cloud mode):** If you opt to use the Gemini-powered AI conversation instead of the on-device model, the text of that conversation is sent to Google's Gemini API. This is subject to Google's own privacy policy and terms of service.

We do not sell your data. We do not use your data for advertising.

### 4.4 On-Device AI

If you use the on-device AI model (LiteRT / Gemma), all inference runs locally. No conversation data leaves your device.

---

## 5. Data Retention and Deletion

- All locally stored data (karma history, sessions, todos, layout) can be cleared by uninstalling the App or through Android's standard "Clear Data" option in device settings.
- If you have an account, you may sign out at any time; doing so clears all stored tokens from your device.
- We do not currently offer a server-side account deletion endpoint. If backend data deletion is important to you, please contact us (see §11).

---

## 6. Third-Party Services

The App may interact with:

- **Google Sign-In / Credential Manager API** — governed by [Google's Terms of Service](https://policies.google.com/terms) and [Privacy Policy](https://policies.google.com/privacy)
- **Google Gemini API** (optional, cloud AI mode) — governed by Google's Generative AI Terms

We are not responsible for the practices of these third-party services.

---

## 7. Open Source

MindfulHome is released under the **MIT License**. The source code is available at [github.com/fdondi/mindfulhome](https://github.com/fdondi/mindfulhome). Your rights and obligations under the MIT License are separate from these Terms of Service, which govern your use of the App as an end user.

---

## 8. No Warranty

THE APP IS PROVIDED "AS IS" AND "AS AVAILABLE," WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, OR NON-INFRINGEMENT.

We do not warrant that:
- The App will be uninterrupted or error-free
- The karma or AI systems will produce any particular behavioral outcome
- The App will be compatible with all Android devices or launchers

---

## 9. Limitation of Liability

TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT SHALL FRANCESCO DONDI OR CONTRIBUTORS BE LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES ARISING OUT OF OR RELATED TO YOUR USE OF THE APP, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

The App is a productivity and wellness tool. It is not a substitute for professional medical, psychological, or therapeutic advice.

---

## 10. Changes to These Terms

We may update these Terms from time to time. When we do, we will update the "Last Updated" date above. Continued use of the App after changes are posted constitutes acceptance of the updated Terms. For significant changes, we will provide notice within the App where reasonably possible.

---

## 11. Contact

For questions about these Terms or data-related requests:

**Francesco Dondi**
GitHub: [github.com/fdondi](https://github.com/fdondi)

---

## 12. Governing Law

These Terms are governed by the laws of the jurisdiction in which the developer is domiciled, without regard to conflict-of-law principles. Any disputes shall be resolved in the competent courts of that jurisdiction.

---

*These Terms apply to MindfulHome as a standalone application. They do not create an employment, partnership, or agency relationship between you and the developer.*
