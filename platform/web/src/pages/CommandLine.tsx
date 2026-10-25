import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useSession } from '../session'
import { Help, Icon, PageHead } from '../ui'

/** Where the source and the releases are (the images' org.opencontainers.image.source). */
const SOURCE = 'https://github.com/bitboyro/mcpgp'

/** A command to copy, in the dark code style. */
function Command({ label, text }: { label: string; text: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // no clipboard (an insecure page): the text is there to select
    }
  }
  return (
    <div className="stage-card" style={{ background: 'var(--stage)', gap: 8 }}>
      <div className="card-head">
        <span className="eyebrow">{label}</span>
        <button type="button" className="btn btn-sm btn-stage" onClick={() => void copy()} aria-label={`Copy: ${label}`}>
          <Icon name={copied ? 'check' : 'copy'} />{copied ? 'Copied' : 'Copy'}</button>
      </div>
      <pre className="code" style={{ margin: 0 }}>{text}</pre>
    </div>
  )
}

/**
 * The mcpgp command-line tool (docs/spec/cli.md): where to get the version that matches this platform, how to use
 * it on a machine or in CI, and how it pushes definitions to this platform.
 */
export function CommandLinePage() {
  const { settings } = useSession()
  const version = settings.build?.version ?? null
  const v = version ?? '<version>'
  const image = settings.cli?.image ?? `docker.io/<account>/mcpgp-cli:${v}`
  const platform = settings.publicUrl || window.location.origin
  const release = version ? `${SOURCE}/releases/tag/v${version}` : `${SOURCE}/releases`
  const docs = (path: string) => `${SOURCE}/blob/${version ? 'v' + version : 'main'}/${path}`
  return (
    <>
      <PageHead title="Command line" sub="mcpgp, the command-line tool: build, check and test MCP servers on your machine or in CI, and push them to this platform." />
      <div className="body">
        <section className="col col-wide" aria-label="Command line">
          <div className="card">
            <h2>Get it</h2>
            <Help text={`Use mcpgp ${v}, the version of this platform, so both compile the same way.`}
              more="Pin it in your repository's .mcpgp/config.yaml (cli: …); a different CLI version refuses to run (exit code 5) unless --ignore-version is given." />
            <div className="rows">
              <div style={{ padding: '12px 0' }} className="list">
                <b style={{ fontSize: 15 }}>For CI: the Docker image</b>
                <span className="hint">Nothing to install; mcpgp and git are on the PATH. Pin it by tag, or by digest.</span>
                <Command label="Image" text={image} />
                {!settings.cli?.image && <span className="hint">This platform was built locally, so it doesn't know your registry: fill in the account that publishes your images.</span>}
              </div>
              <div style={{ padding: '12px 0' }} className="list">
                <b style={{ fontSize: 15 }}>On a machine: the zip (Java 21 or later)</b>
                <span className="hint">Unzip it and put its bin folder on your PATH; check it against SHA256SUMS on the same page.</span>
                <div><a className="btn btn-sm" href={release} target="_blank" rel="noreferrer"><Icon name="download" />mcpgp-cli-{v}.zip on the release page</a></div>
                <span className="hint">The same page has mcpgp-cli-{v}.jar, which runs with <span className="mono">java -jar</span>.</span>
              </div>
            </div>
          </div>

          <div className="card">
            <h2>Use it</h2>
            <Help text="Everything the platform does with a definition, locally." more="mcpgp test starts the server against a mock made from your API definitions, so no real API or credentials are needed. mcpgp try calls a tool as an agent would." />
            <Command label="Start a server from an API definition" text={'mcpgp init --from-spec openapi.yaml --name orders-mcp'} />
            <Command label="Check, test and build it" text={['mcpgp validate orders-mcp.mcpgp.yaml', 'mcpgp test orders-mcp.mcpgp.yaml', 'mcpgp build orders-mcp.mcpgp.yaml --frozen-lock'].join('\n')} />
            <Command label="In CI, with the image" text={`docker run --rm -v "$PWD":/work -w /work ${image} validate --all`} />
          </div>

          <div className="card">
            <h2>Push to this platform</h2>
            <Help text="mcpgp push uploads a definition as its server's next revision, and can build and release it."
              more="It sends the definition project, not a package; the platform builds and signs it with the same compiler. A server the platform doesn't have is created. --build waits for the build; --verify-local checks the platform compiles it exactly as your CLI does; --release releases it (an agent's token asks a person to approve instead)." />
            <ol className="hint" style={{ margin: 0, paddingLeft: 18, display: 'flex', flexDirection: 'column', gap: 4 }}>
              <li>Create a pipeline token on <Link to="/agents">API and agents</Link> (shown once; keep it in your CI's secret store).</li>
              <li>Set it as <span className="mono">MCPGP_TOKEN</span>, never as a flag, so it stays out of shell history and logs.</li>
            </ol>
            <Command label="Push and build" text={[`export MCPGP_PLATFORM=${platform}`, 'export MCPGP_TOKEN=…            # the pipeline token', 'mcpgp push orders-mcp.mcpgp.yaml --build'].join('\n')} />
          </div>

          <p className="hint">Every command and option: <a href={docs('docs/spec/cli.md')} target="_blank" rel="noreferrer">the CLI reference</a> ·
            {' '}<a href={docs('docs/guides/ci-pipeline.md')} target="_blank" rel="noreferrer">setting up a CI pipeline</a>.</p>
        </section>
      </div>
    </>
  )
}
