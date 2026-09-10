# Contributing

Bug reports, questions, ideas and code are all welcome. Everything goes through GitHub
issues and pull requests in this repository; there is no separate tracker.

## Reporting a problem

Open an issue with the bug report form. What makes a report useful here:

- **`~/.viskly/viskly.log`.** The packaged application has no console, so that file is
  the only record of what went wrong.
- **The exact macOS version and the Mac**, Apple Silicon or Intel. Almost everything hard
  in this codebase touches a system API: the event tap, the synthetic paste, the three
  consents.
- **The Viskly version**, shown at the bottom of the settings window's sidebar.
- **Steps to reproduce**, especially for anything involving permissions. macOS never
  reports a missing consent to the application, so "the shortcut does nothing" has at least
  four causes.

Questions and ideas can be a plain issue without the form.

## Getting the source

```bash
git clone https://github.com/<your-account>/viskly.git
cd viskly
git remote add upstream https://github.com/viskly/viskly.git
git switch -c my-change
```

## Building and testing

You need macOS, JDK 25 and Maven.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
mvn verify                          # compile and run the tests
mvn spring-boot:run                 # run it from the terminal
./scripts/build-app.sh --install    # build Viskly.app into /Applications
```

Every build of the app is a new application to macOS, so its Input Monitoring and
Accessibility consents have to be granted again. While iterating, `mvn spring-boot:run`
with the consents given to your terminal saves a lot of that.

[DEVELOPMENT.md](DEVELOPMENT.md) has the architecture, the conventions and the list of
traps that have already cost time. Read the traps before touching the hotkey, the paste or
the build script.

There is no formatter configured: match the code around your change. Tests assert what
they check; a test that only prints is not a test.

## Pull requests

- **Open an issue first** for anything bigger than a typo, and say you intend to work on
  it. It is cheaper to agree on the approach before the code exists.
- **Title the pull request after the issue**: `#123: Paste into terminal windows`.
- **One change per pull request**, based on the current `main`.
- **Commit messages** have a subject that says what changed and a body that says why.
- **`mvn verify` passes**, and the description says what you ran by hand, on which macOS
  version and which Mac. Much of this code cannot be tested anywhere but a real Mac, so say
  plainly what you could not test.
- **If a tool wrote part of the change**, an AI assistant included, say which parts. The
  licence agreement below depends on knowing what is yours.

The pull request template has the same list as checkboxes.

## Contributor licence agreement

Before the first pull request of yours is merged, you sign the agreement in
[CLA.md](CLA.md). A bot asks for it in the pull request; you sign by replying with:

```
I have read the CLA Document and I hereby sign the CLA
```

Once per person; it covers everything you contribute afterwards.

Why it exists: Viskly is under the GPL-3.0 and I hold the copyright, which keeps a
commercial edition possible later. A licence is something a copyright holder grants to
others, not to themselves, so I can release a future version under different terms. Code
from someone else would end that: their lines stay theirs under the GPL, and relicensing
would need their agreement. Collected afterwards from many contributors, that agreement is
usually impossible to get, which is why several well-known projects could not change their
licence without rewriting whole subsystems first. The CLA settles it up front.

You keep the copyright in your work. The agreement gives me a licence to use it, including
under other terms, plus a patent licence for it.

**A note on the DCO, because it is often mistaken for this:** a Developer Certificate of
Origin certifies that a contributor wrote what they submitted and had the right to submit
it. It does not transfer copyright and it does not grant the right to relicense. Only an
agreement like the CLA does that.

## Code of conduct

Everyone taking part in issues and pull requests is expected to follow the
[code of conduct](CODE_OF_CONDUCT.md).

## Forks

The GPL means you may fork this and do as you like within its terms. The name is a
separate matter: **Viskly** is not covered by the licence, so a fork needs its own name and
its own icon. See the README.
