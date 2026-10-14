"""Exercise workflow argument routing, hosted Deploy boundary, and real CLI preview."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest

import yaml
import test_release_coordinator as fixtures

ROOT = Path(__file__).resolve().parents[1]


def workflow(name):
    # BaseLoader keeps YAML's `on` key as a string rather than a YAML 1.1 boolean.
    return yaml.load((ROOT / '.github/workflows' / name).read_text(), Loader=yaml.BaseLoader)


def shell_step(name, step_name):
    for job in workflow(name)['jobs'].values():
        for step in job['steps']:
            if step.get('name') == step_name:
                return step['run']
    raise AssertionError('Missing workflow step: ' + step_name)


RESOLVE_ACTION = 'resolve-published-dependencies'


def action(name):
    return yaml.load((ROOT / '.github/actions' / name / 'action.yml').read_text(), Loader=yaml.BaseLoader)


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin)+':'+os.environ['PATH'],
                        EVENT_NAME='workflow_dispatch', OPERATION='cut', PACKAGE='all',
                        BUMP='minor', DRY_RUN='false')

    def fake(self, name, code):
        file = self.bin / name
        file.write_text(code)
        file.chmod(0o755)

    def step(self, workflow_name, name, cwd=None):
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', shell_step(workflow_name, name)],
                              cwd=cwd or self.root, env=self.env, text=True, capture_output=True)

    def action_step(self, name, cwd):
        run = action(name)['runs']['steps'][0]['run']
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', run], cwd=cwd, env=self.env,
                              text=True, capture_output=True)

    def route(self):
        self.fake('python3', '#!/usr/bin/env bash\nprintf "%s\\n" "$@"\n')
        result = self.step('release.yml', 'Coordinate batch release')
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.splitlines()

    def test_manual_batch_and_preview_reach_coordinator_without_shell_expansion(self):
        self.env.update(PACKAGE='all', DRY_RUN='true')
        self.assertEqual(self.route(), ['tool/release_coordinator.py', '--operation', 'cut',
                         '--package', 'all', '--bump', 'minor', '--dry-run'])
        self.env.update(PACKAGE='$(exit 91)')
        self.assertIn('$(exit 91)', self.route())

    def test_hourly_schedule_only_resumes_authorized_work(self):
        self.env.update(EVENT_NAME='schedule', OPERATION='cut', DRY_RUN='true')
        self.assertEqual(self.route(), ['tool/release_coordinator.py', '--operation', 'resume', '--scheduled'])

    def test_resume_and_cancel_reach_same_coordinator(self):
        for operation in ['resume', 'cancel']:
            self.env['OPERATION'] = operation
            self.assertEqual(self.route()[1:3], ['--operation', operation])

    def test_workflows_are_valid_and_all_shell_blocks_parse(self):
        packages = json.loads((ROOT / 'tool/release-packages.json').read_text())
        release = workflow('release.yml')
        self.assertEqual(set(release['on']['workflow_dispatch']['inputs']['package']['options']), set(packages)|{'all'})
        self.assertEqual(release['permissions']['actions'], 'write')
        self.assertEqual(release['concurrency']['cancel-in-progress'], 'false')
        self.assertTrue(release['on']['schedule'])
        for file in (ROOT / '.github/workflows').glob('*.yml'):
            parsed = yaml.load(file.read_text(), Loader=yaml.BaseLoader)
            for job in parsed.get('jobs', {}).values():
                for step in job.get('steps', []):
                    if 'run' in step:
                        result = subprocess.run(['bash', '-n'], input=step['run'], text=True, capture_output=True)
                        self.assertEqual(result.returncode, 0, str(file)+': '+result.stderr)
        for file in (ROOT / '.github/actions').glob('*/action.yml'):
            for step in yaml.load(file.read_text(), Loader=yaml.BaseLoader)['runs']['steps']:
                result = subprocess.run(['bash', '-n'], input=step['run'], text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, str(file)+': '+result.stderr)

    def test_deploy_disables_sdk_retries_so_coordinator_owns_cooldown(self):
        self.assertEqual(workflow('deploy.yml')['jobs']['publish']['env']['PUB_MAX_HTTP_RETRIES'], '1')

    # Purpose: Keep historical tag inspection independent of files absent from old source.
    # /**
    #  * @param self The workflow shell fixture with a historical source commit.
    #  * @returns None; asserts manual inspection loads invocation-pinned current tooling.
    #  * @throws AssertionError if checkout of an old tag loses the SDK selector.
    #  */
    def test_manual_historical_inspection_uses_invocation_tooling(self):
        source = self.root / 'source'
        source.mkdir()
        subprocess.run(['git', 'init', str(source)], check=True, capture_output=True)
        subprocess.run(['git', '-C', str(source), 'config', 'user.name', 'Fixture'], check=True)
        subprocess.run(['git', '-C', str(source), 'config', 'user.email', 'fixture@example.invalid'], check=True)
        (source / 'pubspec.yaml').write_text('name: fixture\nversion: 1.0.0\n')
        subprocess.run(['git', '-C', str(source), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(source), 'commit', '-m', 'old source'], check=True, capture_output=True)
        old = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
        shutil.copytree(ROOT / 'tool', source / 'tool', ignore=shutil.ignore_patterns('__pycache__'))
        subprocess.run(['git', '-C', str(source), 'add', 'tool'], check=True)
        subprocess.run(['git', '-C', str(source), 'commit', '-m', 'current tooling'], check=True, capture_output=True)
        invocation = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
        subprocess.run(['git', '-C', str(source), 'checkout', old], check=True, capture_output=True)
        self.env.update(INPUT_EVENT_NAME='workflow_dispatch', GITHUB_SHA=invocation,
                        RUNNER_TEMP=str(self.root), GITHUB_WORKSPACE=str(source),
                        GITHUB_ENV=str(self.root / 'env'))
        self.fake('python3', '#!/usr/bin/env bash\nif [ "$1" = "-m" ]; then exit 0; fi\ntest -f "$1" || exit 91\nprintf "%s\n" "$@"\n')
        result = self.step('deploy.yml', 'Select committed packaging SDK', source)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('--inspect', result.stdout.splitlines())
        self.assertFalse((source / 'tool/release_toolchain.py').exists())
        self.assertEqual(subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip(), old)

    def test_deploy_uploads_only_from_original_tag_push(self):
        steps = {step['name']: step for step in workflow('deploy.yml')['jobs']['publish']['steps'] if 'name' in step}
        self.assertIn("github.event_name == 'push'", steps['Publish']['if'])
        self.assertIn("github.event_name != 'push'", steps['Validate publish (dry run)']['if'])
        self.assertEqual(steps['Validate publish (dry run)']['run'], 'flutter pub publish --dry-run')

    def test_deploy_native_build_and_publish_share_published_dependency_resolution(self):
        jobs = workflow('deploy.yml')['jobs']
        uses = {name: [step['uses'] for step in jobs[name]['steps'] if RESOLVE_ACTION in step.get('uses', '')]
                for name in ('native-build', 'publish')}
        # Local action paths resolve from the workspace root, not a checkout `path:`.
        self.assertEqual(uses['native-build'], ['./simple-sms/.github/actions/' + RESOLVE_ACTION])
        self.assertEqual(uses['publish'], [])
        publish_resolution = next(step for step in jobs['publish']['steps']
                                  if step.get('name') == 'Resolve published dependencies')
        self.assertIn('flutter pub get --no-example', publish_resolution['run'])
        native = next(step for step in jobs['native-build']['steps'] if RESOLVE_ACTION in step.get('uses', ''))
        self.assertEqual(native['with'], {'directory': 'simple-sms'})
        # Resolve before anything that compiles or uploads.
        for name, before in (('native-build', 'Compile native side (debug APK)'), ('publish', 'Publish')):
            names = [step.get('name') for step in jobs[name]['steps']]
            self.assertLess(names.index('Resolve published dependencies'), names.index(before), name)

    def test_deploy_native_build_does_not_check_out_sibling_repositories(self):
        for job in ('native-build', 'publish'):
            for step in workflow('deploy.yml')['jobs'][job]['steps']:
                self.assertNotIn('repository', step.get('with', {}), job + ': ' + step.get('name', ''))

    def test_deploy_removes_overrides_before_hosted_resolution(self):
        package = self.root / 'package'
        package.mkdir()
        (package / 'pubspec.yaml').write_text('name: fixture\nversion: 1.0.0\n')
        (package / 'pubspec_overrides.yaml').write_text('dependency_overrides: {}\n')
        example = package / 'example'; example.mkdir()
        (example / 'pubspec_overrides.yaml').write_text('dependency_overrides: {}\n')
        (self.root / 'calls').write_text('')
        self.fake('flutter', '''#!/usr/bin/env bash
if [ -n "$(find . -name pubspec_overrides.yaml -type f -print -quit)" ]; then exit 91; fi
if [ "$1 $2" != 'pub get' ]; then exit 92; fi
echo "$PWD $*" >> "''' + str(self.root / 'calls') + '''"
''')
        result = self.action_step(RESOLVE_ACTION, package)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((package / 'pubspec_overrides.yaml').exists())
        self.assertFalse((example / 'pubspec_overrides.yaml').exists())
        # Root without its example, then the example, both against hosted packages.
        self.assertEqual((self.root / 'calls').read_text().splitlines(),
                         [str(package.resolve()) + ' pub get --no-example', str(example.resolve()) + ' pub get'])

    # Purpose: Keep example-generated artifacts out of the publication checkout.
    # /**
    #  * @param self The workflow fixture with a controlled hosted-resolution SDK.
    #  * @returns None; asserts publication resolves the package without its example.
    #  * @throws AssertionError if Deploy packaging creates extra example files.
    #  */
    def test_publish_resolution_does_not_generate_example_artifacts(self):
        package = self.root / 'publication'
        example = package / 'example'
        example.mkdir(parents=True)
        (package / 'pubspec_overrides.yaml').write_text('dependency_overrides: {}\n')
        (example / 'pubspec_overrides.yaml').write_text('dependency_overrides: {}\n')
        self.fake('flutter', '#!/usr/bin/env bash\n'
                  'if [ "$PWD" = "' + str(example.resolve()) + '" ]; then touch GeneratedPluginRegistrant.swift; fi\n')
        step = next(value for value in workflow('deploy.yml')['jobs']['publish']['steps']
                    if value.get('name') == 'Resolve published dependencies')
        if 'uses' in step:
            result = self.action_step(RESOLVE_ACTION, package)
        else:
            result = subprocess.run(['bash', '-euo', 'pipefail', '-c', step['run']],
                                    cwd=package, env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((example / 'GeneratedPluginRegistrant.swift').exists())
        self.assertFalse((package / 'pubspec_overrides.yaml').exists())
        self.assertFalse((example / 'pubspec_overrides.yaml').exists())

    def test_resolve_action_tolerates_missing_override_files(self):
        package = self.root / 'package'
        (package / 'example').mkdir(parents=True)
        self.fake('flutter', '#!/usr/bin/env bash\n')
        self.assertEqual(self.action_step(RESOLVE_ACTION, package).returncode, 0)

    def test_actual_cli_preview_uses_source_graph_and_leaves_remote_untouched(self):
        fixture = fixtures.CoordinatorTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        root = fixture.root
        (root / 'tool').mkdir()
        for file in ROOT.glob('tool/release_*.py'):
            shutil.copy2(file, root / 'tool' / file.name)
        (root / 'tool/release-packages.json').write_text(json.dumps(fixture.fixture.packages))
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append(self.path)
                response = [] if self.path.startswith('/repos/') else fixture.pub.package(self.path.rsplit('/',1)[1])
                self.send_response(200); self.end_headers()
                self.wfile.write(json.dumps(response).encode())
            def do_POST(self):
                calls.append('MUTATION'); self.send_error(500)
            do_PATCH = do_POST
            def log_message(self, *args): pass
        server = ThreadingHTTPServer(('127.0.0.1',0), Handler)
        threading.Thread(target=server.serve_forever,daemon=True).start()
        self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
        url = f'http://127.0.0.1:{server.server_port}'
        (self.bin / 'flutter').symlink_to(root / 'fake-flutter')
        env = {**fixture.repo.env, **self.env, 'GITHUB_API_URL':url, 'PUB_HOSTED_URL':url,
               'GH_TOKEN':'fixture-token','GITHUB_REPOSITORY':'owner/repo','GITHUB_RUN_ID':'12345'}
        before = fixture.fixture.git('ls-remote','origin')
        result = subprocess.run([sys.executable, 'tool/release_coordinator.py', '--dry-run'],
                                cwd=root, env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertIn('demo_android',result.stdout)
        self.assertTrue(fixture.trace.exists())
        self.assertEqual(fixture.fixture.git('ls-remote','origin'),before)
        self.assertNotIn('MUTATION',calls)
        self.assertIsNone(fixture.journal.load())


if __name__ == '__main__': unittest.main()
