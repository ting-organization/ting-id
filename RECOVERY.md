# Recovery phrases

A recovery phrase is twelve words from which Ting ID makes one key. That key is
one of the keys of an identity, so whoever has the words can act for it as any
of its devices can. The identity itself is not the words: it keeps its ID and
its accounts when keys change, and guardians can replace a lost or stolen
phrase ([the registry](https://github.com/ting-organization/ting/blob/master/node/wiki/programs/identity.md)).

## From words to key

1. **Words.** [BIP-39](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki)
   with the English word list. A new phrase is 12 words from 16 random bytes; a
   restore accepts 12, 15, 18, 21 or 24 words whose checksum holds. Words are
   compared in lower case, one space apart. There is no passphrase.
2. **Seed.** The BIP-39 seed of the words: PBKDF2-HMAC-SHA512, 2,048 rounds,
   salt `"mnemonic"`.
3. **Key.** [BIP-32](https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki)
   on secp256k1 at

   ```text
   m/44'/1414090311'/0'/0/0
   ```

   where 1414090311, the coin type, is `"TING"` read as a big-endian number.
4. **Use.** The key is a key of the standard secp256k1 policy: its parameters
   are the 65-byte uncompressed public key, and the identity lists it as
   `canonical secp256k1 policy ‖ public key`.

The phrase `abandon abandon abandon abandon abandon abandon abandon abandon
abandon abandon abandon about` gives the public key

```text
047388c4cccd1893555407977f70e303c242c3d3d6f992ea01583af171a2c02c67136ba4103689143e9d458d7891d2c0c1ea98db313a9aa72ea76bcbbb6ebde46b
```

## Finding the identity

An identity's ID is the hash of its whole creation input, guardians, delays and
salt included, so the words cannot give it. A restore finds it instead:

1. Ask a node for the registry's Created events: `/v1/events` with the
   registry as `contract` and `H("app/id-v1/event/created")` as `topic`. Each
   names an ID, and every identity was made by one.
2. For each ID read the core record and the key records of its current key
   epoch, with their proofs.
3. The identities whose keys include the phrase's key are the phrase's.

Events come from any node, so a node can leave an identity out but never make
one up; asking another node covers the first. When no identity lists the key,
a recovery or a key change has replaced it, and the words no longer act for
that identity.

## On the phone

- The words are kept in the phone's keystore, apart from the key they give.
  Signing reads only the key. Showing the words asks the phone to confirm its
  owner: where the phone has a strong fingerprint or face unlock, the keystore
  itself enforces it, and the phone forgets the words when a new fingerprint
  or face is added; elsewhere the app asks for the phone's unlock first.
- Nothing goes into the phone's backups: the written words are the only copy of
  the key outside the phone.
- Screenshots are blocked while the words are on screen.

## With guardians

- **Phone lost, words kept:** restore on a new phone.
- **Words lost:** the phone still acts; making new words adds their key with a
  change, which waits the identity's change delay.
- **Words or phone stolen:** the thief can spend from the instant account at
  once, but withdrawals from the savings account wait its lock and can be
  cancelled, and key changes wait the change delay. A guardian recovery, which
  waits less, installs the key of new words and voids the thief's pending
  changes; the old words then stop working.
