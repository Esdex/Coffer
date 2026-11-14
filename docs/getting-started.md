# Getting started

## Install

Download the latest APK from [GitHub Releases](https://github.com/Esdex/Coffer/releases/latest)
and install it. Coffer needs Android 11 or later.

## Sign in to Telegram

Coffer is a Telegram app of its own: it signs in to your Telegram account, the same way Telegram
Desktop or a second phone does. Your vault will live in that account's Saved Messages.

You can sign in in two ways:

- **Phone number.** Type the country code and the number. Telegram sends a code, usually as a
  message from "Telegram" in the Telegram app on your other devices, sometimes by SMS. Type it
  into the cells.
- **QR code.** Tap "Sign in with a QR code instead". On a phone where you are signed in, open
  Telegram, go to Settings, Devices, Link Desktop Device, and scan the code.

If your account has two-step verification, Coffer then asks for your Telegram cloud password.

Coffer uses its own built-in Telegram API keys, so you need nothing from my.telegram.org. You
can use keys of your own instead: see [Telegram API keys](api-keys.md).

## Create your vault

After signing in, Coffer looks through Saved Messages for a vault.

If there is none, it offers to create one:

1. **Choose a password.** At least 10 characters, and hard enough to guess that a thousand billion
   tries would not find it. Anyone who gets into your Telegram account could try to guess it offline,
   so make it strong: three or four unrelated words work well. The padlock over the field shuts once
   the password will do.
2. **Write down your recovery key.** 24 words that open the vault if you forget the password.
   Write them on paper, in order, and keep them somewhere safe. See
   [Your recovery key](recovery-key.md).
3. **Check the words.** Coffer asks for a few of them from your paper copy.
4. **Fingerprint unlock.** Coffer offers to open the vault with your fingerprint or face from now
   on. You can turn this on or off later in Settings.
5. **Read the note on Saved Messages.** Two pages explain which messages Coffer keeps there and why
   they must be left alone. See [Leave Coffer's messages as they are](saved-messages.md).

If Coffer finds a vault, for example one you made on another phone, it asks for its password
instead. You can also open it with the recovery key.

## Add your first files

Tap **+** and choose **Add files** or **Add a folder**. Each file is encrypted on the phone and
sent to Saved Messages. The Transfers screen shows what is on its way.
