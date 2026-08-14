import { createCouponIssueTest } from './common.js';

const test = createCouponIssueTest('OPTIMISTIC');

export const options = test.options;
export const setup = test.setup;
export const teardown = test.teardown;
export const handleSummary = test.handleSummary;

export default test.run;
