# Privacy policy

Adv Notes is an open-source note-taking app. This page describes what data the app handles. The developer runs no servers for the app and receives none of your data.

_Last updated: 2026-11-16_

## Your notes

Notebooks, pages, handwriting, imported PDFs and images are stored on your device, in the app's private storage. Nothing leaves your device unless you turn on one of the features below.

## Google Drive sync (optional)

If you connect Google Drive, the app asks for the `https://www.googleapis.com/auth/drive.file` scope. This lets the app see and change **only the files it created itself**, in an "Adv Notes" folder in your Drive. It cannot read your other Drive files.

- Your notebooks and their imported PDFs and images are uploaded to, and downloaded from, that folder.
- The access token and your Google account email are kept on your device. The email is shown in settings so you can see which account is connected.
- Data goes directly between your device and Google. It does not pass through any server run by the developer.
- You can disconnect at any time in the app's settings, which revokes the app's access. You can also remove access at https://myaccount.google.com/permissions and delete the folder from Drive.

The app's use of information received from Google APIs adheres to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements.

## AI features (optional)

When you use the solver, handwriting-to-text with an AI engine, or the AI chat tool, the content you select (an image of your selection or page, and any text you type) is sent to the AI provider **you choose and configure**: OpenAI, Anthropic, OpenRouter, a ChatGPT sign-in, a custom endpoint you enter, or an on-device model that sends nothing anywhere. Your API keys and sign-in tokens are stored on your device in encrypted storage. Each provider's own privacy policy applies to what they receive. Nothing is sent to an AI provider unless you start a request.

## Other network use

- **Tool marketplace:** the app downloads the list of available tools and their files from a public GitHub repository. No personal data is sent beyond what any web request includes (such as your IP address).
- **Update check (can be turned off in settings):** when the library opens, the app asks GitHub for the latest release of this project and, if you tap Update, downloads the APK from it. No personal data is sent beyond what any web request includes.
- **On-device model downloads:** model files are downloaded from Hugging Face when you ask for them.
- **Web tool:** pages you open in the built-in browser tool are loaded from the sites you visit.

## What the app does not do

No analytics, no advertising, no tracking, and no sale or sharing of your data. The developer does not collect crash reports or usage data.

## Deleting your data

Uninstalling the app removes everything stored on the device. Data synced to Google Drive stays in your Drive until you delete the "Adv Notes" folder.

## Contact

Questions: open an issue at https://github.com/moondotgz/adv-notes/issues
