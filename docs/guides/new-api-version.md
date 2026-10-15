# A new version of your API

Your MCP server was made from one version of your API. Now there is another. This guide is the
order of steps: first what you (the server's builder) decide and do, then what the people who run
the server for their target systems (your customers) do.

## 1. Decide what kind of change it is

| The new spec… | Do this | Your customers' agents see |
|---|---|---|
| updates the version you have (new endpoints, fixes; old calls still work) | **Update that version** (§2) | the same server, a few new or changed tools |
| is a new version, and some customers keep running the old one | **Add a version** next to it (§3) | one server; each target system uses its own version |
| replaces the old one for everyone | **Add a version**, make it the default, and remove the old one later (§3, §6) | one server, the new version |
| is a different API in practice (most paths and models changed) | **A separate server** (§4) | a new server, set up next to the old one |

If you're not sure, add a version (§3): nothing changes for customers whose systems still run the old
one, and the **Changes** page shows what agents would notice before you release.

## 2. Update the version you have

On the platform:

1. Open the server, then **Sources**.
2. Next to the version, **Check for updates** if it was fetched from an address; otherwise **Replace**
   and upload the new spec (or choose **From an address**).
3. Read what the page says agents would notice: added and removed tools, changed inputs, and which
   changes are **breaking**. "No changes" means the spec is the same; nothing is saved.
4. **Save as the next version**. The tools keep their names (the names are locked per operation).
5. **Build**, check the build, then **Release** it. The release's "What changed since the last
   release" is the list for your customers.

With the CLI, replace the spec file in your project, then:

```bash
mcpgp diff --against last-release
mcpgp build
```

`mcpgp diff --fail-on-breaking` in CI stops a release with breaking changes (exit code 6).

## 3. Add a version next to the old one

1. **Sources → Add a spec version**: upload the new spec, or **From an address**
   (`https://api.acme.com/v2/openapi.json`; an administrator allows the host first).
2. The version comes from the spec's `info.version`; change it if needed (`2.0`).
3. **Make it the default version** or not:
   - the default is what a target system uses when nothing else says which version it runs;
   - the server's own version follows it: making `2.0` the default makes the next build `2.0.0`.
4. Check what agents would notice, then **Save as the next version**.
5. **Connections → Which version a customer's server runs**: say how a target system tells its
   version (for example `GET /version`, a field `/version` in the answer, under each version's base
   path). Without this, every target set to `auto` uses the default version, so a customer whose
   system still runs the old version must set it by hand.
6. **Tools**: each tool's **Calls** lists the versions it exists in, and a field that exists in only
   one version says so ("2.0 only"). A call to a target on a version without the tool gets a clear
   `NOT_AVAILABLE_ON_INSTANCE` instead of a failed request.
7. **Build**, then **Release**.

What stays the same: tool names (the same operation keeps its name across versions), the customers'
config files, and their MCP client setup.

## 4. A separate server

When the new version is a different API in practice, make it its own server:

- **All servers → Duplicate** copies the server; replace its spec on **Sources**. The copy is
  independent from then on.
- **All servers → Variant** extends the server and changes only what differs (its own spec version,
  rules or naming); fixes to the original reach the variant.

Customers then install the new server next to the old one and move their agents when ready.

## 5. What your customers do

Your customers run the server for their target systems. With a new release:

1. **Get the new package** (zip, jar or image) and replace the old one. Their config file stays as
   it is.
2. **Choose the version for each target system**, in their config:

   ```yaml
   instances:
     - id: eu
       baseUrl: https://orders.eu.acme.com
       contract: auto        # the default: the server asks the target which version it runs
     - id: legacy
       baseUrl: https://orders.legacy.acme.com
       contract: "1.0"       # always 1.0; the target is never asked
     - id: us
       baseUrl: https://orders.us.acme.com
       prefer: "1.0"         # auto, but asks about 1.0 first
   ```

   - `auto` uses the version the target reports when the server can ask (you set that up in §3,
     step 5). If the target can't tell, it uses the default version, or refuses calls to that target
     if you chose so.
   - Without a way to ask, `auto` means the default version, so a target on the old version needs
     `contract: "1.0"`.
3. **Check it** before restarting:

   ```bash
   java -jar orders-mcp.jar doctor
   ```

   For every target it shows the version the target reports and the one the server will use, and
   warns when a target set to `"1.0"` actually runs `2.0`.
4. **Restart** the server (a config change alone is picked up without a restart).
5. Agents see the versions in `instances_list`; nothing changes in their tool names.

The package's own docs (`docs/CONFIGURATION.md`, "Versions of the … API") describe the same
choices for that server.

## 6. Retiring the old version

When no customer runs the old version any more:

1. Make sure the new version is the default.
2. Remove the old version from the definition (its entry under `contracts`, and its files). In
   the web UI there is no button for this yet; edit the definition file and upload the project, or
   use the API's edits.
3. Build and release. The Changes page lists the tools that existed only in the old version as
   removed: that's the breaking change customers need to hear about.
4. Customers who pinned `contract: "1.0"` must change it first: a pinned version that isn't in the
   package stops the server at startup with a clear message.
