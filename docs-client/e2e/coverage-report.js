/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

const fs = require('node:fs');
const path = require('node:path');

const { createCoverageMap } = require('istanbul-lib-coverage');
const libReport = require('istanbul-lib-report');
const reports = require('istanbul-reports');

const projectDir = path.resolve(__dirname, '..');
const sourceDir = path.join(projectDir, 'src');
const coverageDir = path.join(projectDir, 'build', 'playwright', 'coverage');
const rawCoverageDir = path.join(projectDir, 'build', 'e2e-coverage');
const rawDir = path.join(rawCoverageDir, 'raw');
const reportDir = path.join(coverageDir, 'report');

const thresholds = {
  lines: 95,
  functions: 95,
  statements: 95,
  branches: 90,
};

function listSourceFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      return listSourceFiles(entryPath);
    }
    return /\.tsx?$/.test(entry.name) ? [entryPath] : [];
  });
}

function normalize(file) {
  return path.resolve(file).split(path.sep).join('/');
}

function clean() {
  fs.rmSync(coverageDir, { recursive: true, force: true });
  fs.rmSync(rawCoverageDir, { recursive: true, force: true });
}

function report() {
  if (!fs.existsSync(rawDir)) {
    throw new Error(`Coverage output is missing: ${rawDir}`);
  }

  const coverageFiles = fs
    .readdirSync(rawDir)
    .filter((file) => file.endsWith('.json'));
  if (coverageFiles.length === 0) {
    throw new Error(`Coverage output is empty: ${rawDir}`);
  }

  const coverageMap = createCoverageMap({});
  for (const file of coverageFiles) {
    coverageMap.merge(
      JSON.parse(fs.readFileSync(path.join(rawDir, file), 'utf8')),
    );
  }

  const coveredFiles = new Set(coverageMap.files().map(normalize));
  const missingFiles = listSourceFiles(sourceDir)
    .map(normalize)
    .filter((file) => !coveredFiles.has(file));
  if (missingFiles.length > 0) {
    throw new Error(
      `Source files missing from coverage:\n${missingFiles.join('\n')}`,
    );
  }

  fs.mkdirSync(reportDir, { recursive: true });
  const context = libReport.createContext({
    coverageMap,
    dir: reportDir,
  });
  for (const type of ['text-summary', 'html', 'lcovonly', 'json-summary']) {
    reports.create(type).execute(context);
  }

  const summary = coverageMap.getCoverageSummary().toJSON();
  const failures = Object.entries(thresholds)
    .filter(([metric, minimum]) => summary[metric].pct < minimum)
    .map(
      ([metric, minimum]) =>
        `${metric}: ${summary[metric].pct}% (required: ${minimum}%)`,
    );
  if (failures.length > 0) {
    throw new Error(
      `Coverage thresholds were not met:\n${failures.join('\n')}`,
    );
  }
}

const command = process.argv[2];
if (command === 'clean') {
  clean();
} else if (command === 'report') {
  report();
} else {
  throw new Error(`Unknown command: ${command}`);
}
