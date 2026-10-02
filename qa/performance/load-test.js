// k6 load test: CI pipelines uploading reports while engineers browse the dashboard.
//
//   k6 run qa/performance/load-test.js                        (against localhost:8080)
//   k6 run -e API_URL=http://staging:8080 qa/performance/load-test.js
//
// The thresholds are the performance budget: if any is exceeded, k6 exits non-zero and CI fails.

import { check, sleep } from 'k6'
import http from 'k6/http'
import { Counter } from 'k6/metrics'

const API = __ENV.API_URL || 'http://localhost:8080'
const DURATION = __ENV.DURATION || '30s'
const TESTS_PER_REPORT = 50
const READ_VUS = Number(__ENV.READ_VUS || 20)

const uploadedResults = new Counter('uploaded_test_results')

export const options = {
  scenarios: {
    // Open model: a fixed number of uploads per second, regardless of how slow the server gets.
    ci_uploads: {
      executor: 'constant-arrival-rate',
      exec: 'uploadReport',
      rate: Number(__ENV.UPLOADS_PER_SECOND || 10),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
    // Closed model: N engineers refreshing the dashboard.
    dashboard_reads: {
      executor: 'ramping-vus',
      exec: 'browseDashboard',
      startVUs: 0,
      stages: [
        { duration: '10s', target: READ_VUS },
        { duration: DURATION, target: READ_VUS },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'http_req_duration{scenario:ci_uploads}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{scenario:dashboard_reads}': ['p(95)<300'],
  },
}

function buildReport(iteration) {
  const cases = []
  for (let i = 0; i < TESTS_PER_REPORT; i++) {
    // Tests 0-4 are flaky (30% failure), the rest always pass.
    const fails = i < 5 && Math.random() < 0.3
    const failure = fails ? '<failure message="Timed out after 500 ms waiting for inventory"/>' : ''
    cases.push(`<testcase classname="load.Suite${i % 5}" name="test${i}" time="0.12">${failure}</testcase>`)
  }
  return `<testsuite name="load-${iteration}">${cases.join('')}</testsuite>`
}

export function setup() {
  const name = `load-${Date.now()}`
  const response = http.post(`${API}/api/v1/projects`, JSON.stringify({ name }), {
    headers: { 'Content-Type': 'application/json' },
  })
  if (response.status !== 201) {
    throw new Error(`Could not create project: ${response.status} ${response.body}`)
  }
  return { projectId: response.json('id'), apiKey: response.json('apiKey') }
}

export function uploadReport(data) {
  const commit = Math.floor(Math.random() * 0xfffffff).toString(16).padStart(7, '0')
  const response = http.post(
    `${API}/api/v1/runs?commitSha=${commit}&branch=main&buildId=${__VU}-${__ITER}-${Date.now()}`,
    buildReport(__ITER),
    { headers: { 'Content-Type': 'application/xml', 'X-API-Key': data.apiKey }, tags: { name: 'POST /runs' } },
  )
  if (check(response, { 'upload accepted (201)': (r) => r.status === 201 })) {
    uploadedResults.add(TESTS_PER_REPORT)
  }
}

export function browseDashboard(data) {
  const responses = http.batch([
    ['GET', `${API}/api/v1/projects/${data.projectId}`, null, { tags: { name: 'GET /projects/:id' } }],
    ['GET', `${API}/api/v1/projects/${data.projectId}/tests?verdict=ALL`, null, { tags: { name: 'GET /tests' } }],
    ['GET', `${API}/api/v1/projects/${data.projectId}/runs?size=10`, null, { tags: { name: 'GET /runs' } }],
  ])
  check(responses[0], { 'summary ok': (r) => r.status === 200 })
  check(responses[1], { 'tests ok': (r) => r.status === 200 })
  check(responses[2], { 'runs ok': (r) => r.status === 200 })
  sleep(0.5) // think time: a person reads the page before refreshing
}
