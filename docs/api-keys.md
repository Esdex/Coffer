# Telegram API keys

Every Telegram app identifies itself to Telegram with an API key: an `api_id` and an `api_hash`.

## Built-in keys

Coffer has keys of its own built in, and most people use those. You need nothing from
my.telegram.org.

## Your own keys

Keys of your own make you independent of the built-in ones: if Telegram ever blocks them, yours
keep working.

1. Open [my.telegram.org](https://my.telegram.org) and sign in with your phone number.
2. Go to **API development tools**.
3. Create an application (any title and short name).
4. In Coffer's sign-in screen, choose **Use my own API keys** and copy in `api_id` and `api_hash`.

To go back, choose **Use the built-in API keys**.

The keys only tell Telegram which app is connecting. They give nobody access to your account or
files.

## Builds from source

A build from the source code has the same built-in keys, as they are part of it. A build without
them asks every user for theirs. See "Build from source" in the
[README](https://github.com/Esdex/Coffer#build-from-source).
