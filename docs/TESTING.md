# Testing Cloudwind at home (first run)

You need: the Windows PC on your home Wi-Fi (or wired), and the Android phone (Android 11+).

## 1. PC
1. Make a folder, e.g. `C:\Cloudwind`, and put `cloudwind.exe` and `setup.ps1` in it.
2. Open PowerShell in that folder and run:
   `powershell -ExecutionPolicy Bypass -File .\setup.ps1`
   It creates the config (photos land in `Pictures\Cloudwind`) and shows the pairing QR. It also writes
   `pair.svg`: open it in a browser if the QR in the console looks garbled.
3. Start the agent and leave the window open:
   `.\cloudwind.exe run`
   When Windows Firewall asks, allow **Private networks** (that's how the phone reaches the PC directly).

## 2. Phone
1. Install `Cloudwind.apk` (allow "install unknown apps" for your browser/file manager when asked).
2. Open it, tap **Scan QR code**, scan the code. Allow photos/videos and notifications.
3. Tap **Battery settings** and set Cloudwind to *Unrestricted* (or "Don't optimise").

## 3. Things to try (and what should happen)
| Try | Expect |
| --- | --- |
| On home Wi-Fi with `cloudwind run` open, take a photo | Within about a minute it appears in `Pictures\Cloudwind`; the app says "PC found on your home network" and the last pass shows "sent directly". Tap **Back up now** to skip the wait. |
| Turn the phone's Wi-Fi off (mobile data), take a photo | Small photos still upload to the cloud; the PC window prints `synced …` within ~60 s. |
| On mobile data, record a video bigger than 50 MB | Held back: "waiting for Wi-Fi". Back on Wi-Fi it goes (directly if the PC is on). |
| Turn on **Include all folders**, take a screenshot, on mobile data | Screenshots are Home-only: "waiting for your home network". On home Wi-Fi it goes straight to the PC and never touches the cloud. |
| Close `cloudwind run`, take photos on Wi-Fi | They go to the cloud. Start `cloudwind run` again: it pulls them. |
| Tap **Free up space** | Android asks once; photos move to the phone's trash (restorable ~30 days). |
| On the PC: `.\cloudwind.exe status` | What's waiting, inbox use, and when the phone last checked in. |

## 4. What to send back if something is off
- A screenshot of the app's home screen (the "Last problem" / "Held back" lines matter most).
- The text in the `cloudwind run` window.
- What you did just before.

## Known gaps for this first test
- The Windows build is cross-compiled and was not run on Windows before you (it builds cleanly; the same code passes all tests on Linux).
- Finding the PC uses an encrypted hint relayed through your Worker, so the first direct sync needs internet on both. Local-only discovery (mDNS) isn't built yet.
- Photos edited after backup are kept on the phone (not offered for deletion) and backed up again; they arrive on the PC as a second file.
