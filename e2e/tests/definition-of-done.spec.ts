import { expect, Page, test } from '@playwright/test';
import { simplePdf } from '../support/pdf';

/**
 * The ten "Definition of Done" steps from the project brief, as one real user journey through the browser:
 * real API, real PostgreSQL, the production web build, a local stub of the job source, and the mock AI provider.
 * Run with SCREENSHOTS=1 to refresh the pictures in docs/screenshots.
 */

const CV_LINES = [
  'Jane Doe',
  'jane.doe@example.org | +33 6 12 34 56 78',
  '',
  'Profile',
  'Backend engineer focused on Java services and PostgreSQL databases for logistics teams.',
  '',
  'Experience',
  'Senior Java Developer - Northwind Labs',
  'Jan 2019 - Present',
  '- Designed REST APIs with Spring Boot used by 12 internal teams',
  '- Reduced report generation time by 40% by tuning PostgreSQL queries',
  '- Introduced CI/CD with GitHub Actions and Docker',
  '',
  'Java Developer | Contoso Systems',
  '03/2016 - 12/2018',
  '- Migrated a monolith to microservices on Kubernetes',
  '',
  'Education',
  'Master in Computer Science - University of Lyon 2012 - 2014',
  '',
  'Skills',
  'Java, Spring Boot, PostgreSQL, Docker, Kubernetes, Git',
  '',
  'Languages',
  'English (C1), French (native)',
];

async function shot(page: Page, name: string) {
  if (process.env.SCREENSHOTS) {
    await page.screenshot({ path: `../docs/screenshots/${name}.png`, fullPage: true });
  }
}

test('a job seeker goes from sign-up to a tracked application', async ({ page }) => {
  const email = `jane-${Date.now()}@example.org`;

  await test.step('1. Register and upload a CV', async () => {
    await page.goto('/');
    await shot(page, '01-landing');
    await page.getByRole('link', { name: 'Create a free account' }).click();
    await page.getByLabel('Your name').fill('Jane Doe');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill('correct horse battery');
    await page.getByLabel(/I agree that my CV/).check();
    await shot(page, '02-register');
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page.getByRole('heading', { name: 'My CVs' })).toBeVisible();
    await page.locator('input[type=file]').setInputFiles({ name: 'jane-doe-cv.pdf', mimeType: 'application/pdf', buffer: simplePdf(CV_LINES) });
    await page.getByLabel('Name this version').first().fill('Backend CV');
    await page.getByRole('button', { name: 'Add CV' }).first().click();
    await expect(page.getByRole('heading', { name: 'Review extracted data' })).toBeVisible();
  });

  await test.step('2. Review the extracted profile and correct it', async () => {
    await expect(page.locator('#fullName')).toHaveValue('Jane Doe');
    await expect(page.locator('#email')).toHaveValue('jane.doe@example.org');
    await expect(page.locator('#skills')).toHaveValue(/Spring Boot/);
    await expect(page.locator('#skills')).toHaveValue(/PostgreSQL/);
    await expect(page.getByLabel('Job title').first()).toHaveValue('Senior Java Developer');
    await expect(page.getByLabel('Company').first()).toHaveValue('Northwind Labs');
    await shot(page, '03-review-extracted-profile');

    await page.locator('#summary').fill('Backend engineer who builds reliable Java services and tunes PostgreSQL databases.');
    await page.getByRole('button', { name: 'Save reviewed profile' }).click();
    await expect(page.getByText('Saved. This CV is now marked as reviewed.')).toBeVisible();
  });

  await test.step('3. Run a CV analysis', async () => {
    await page.getByRole('link', { name: 'Analyse this CV' }).click();
    await page.getByRole('button', { name: 'Run analysis' }).click();
    await expect(page.getByRole('heading', { name: 'Quality score' })).toBeVisible();
    await expect(page.getByRole('heading', { name: /Recommendations, most important first/ })).toBeVisible();
    await expect(page.getByText('Skill inventory')).toBeVisible();
    await expect(page.getByText('Not on the').first()).toHaveCount(0);
    await shot(page, '04-cv-analysis');
  });

  await test.step('4. Discover real offers from a permitted source', async () => {
    await page.getByRole('link', { name: 'Jobs', exact: true }).click();
    await page.getByLabel('Keywords').first().fill('');
    await page.getByRole('button', { name: 'Search sources' }).click();
    await expect(page.getByText(/Found 3 new/)).toBeVisible();
    await expect(page.getByText('Arbeitnow: 3 listed')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Senior Java Developer (m/f/d)' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Pastry Chef' })).toBeVisible();
    await shot(page, '05-jobs');
  });

  await test.step('5. Match an offer and understand the score', async () => {
    // Offers are ranked against the chosen CV, best first.
    const cards = page.locator('article.job');
    await expect(cards.first()).toContainText('Senior Java Developer');
    await expect(cards.first().locator('.score')).toContainText('/100');
    await expect(cards.last()).toContainText('Pastry Chef');

    await page.getByRole('link', { name: 'Senior Java Developer (m/f/d)' }).click();
    await page.getByRole('tab', { name: 'Match analysis' }).click();
    await expect(page.getByRole('heading', { name: 'Compatibility' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'How the score is built' })).toBeVisible();
    await expect(page.locator('app-match-report').getByText('not a prediction of being hired')).toBeVisible();
    await expect(page.locator('.skill.yes', { hasText: 'Java' }).first()).toBeVisible();
    await expect(page.locator('.skill', { hasText: 'Kafka' })).toBeVisible(); // preferred, and not on the CV
    await expect(page.getByText('Not evaluated').first()).toBeVisible(); // criteria the offer does not state are skipped
    await shot(page, '06-match-analysis');
  });

  await test.step('6. Generate and edit a motivation letter in the offer language', async () => {
    await page.getByRole('tab', { name: 'Motivation letter' }).click();
    await page.getByRole('button', { name: 'Write a draft' }).click();
    const body = page.locator('#d-body');
    await expect(body).toHaveValue(/^Dear Sir or Madam,/);
    await expect(body).toHaveValue(/Senior Java Developer \(m\/f\/d\) position at Acme GmbH/);
    await expect(body).toHaveValue(/Designed REST APIs with Spring Boot/);
    await expect(page.getByRole('heading', { name: 'Evidence from your CV' })).toBeVisible();
    await shot(page, '07-motivation-letter');

    await body.fill((await body.inputValue()) + '\n\nP.S. I can start in January.');
    await page.getByRole('button', { name: 'Save as a new version' }).click();
    await expect(page.getByText('Saved as version 2.')).toBeVisible();

    // A French offer gets a French letter.
    await page.getByRole('link', { name: 'Back to offers' }).click();
    await page.getByRole('link', { name: 'Développeur Java Spring (H/F)' }).click();
    await page.getByRole('tab', { name: 'Motivation letter' }).click();
    await page.getByRole('button', { name: 'Write a draft' }).click();
    await expect(page.locator('#d-body')).toHaveValue(/^Madame, Monsieur,/);
    await expect(page.locator('#d-body')).toHaveValue(/Je vous prie d'agréer/);
  });

  await test.step('7. Extract a published HR email, and never invent one', async () => {
    // The French offer publishes no address: the app says so and points at the official application link.
    await page.getByRole('tab', { name: 'HR contact' }).click();
    await page.getByRole('button', { name: 'Look for contacts' }).click();
    await expect(page.getByText('No publicly available HR email found')).toBeVisible();
    await expect(page.getByRole('link', { name: /Official application link/ }).first()).toBeVisible();

    // The English offer publishes one, with its source.
    await page.getByRole('link', { name: 'Back to offers' }).click();
    await page.getByRole('link', { name: 'Senior Java Developer (m/f/d)' }).click();
    await page.getByRole('tab', { name: 'HR contact' }).click();
    await page.getByRole('button', { name: 'Look for contacts' }).click();
    await expect(page.getByText('careers@acme-corp.io')).toBeVisible();
    await expect(page.getByText('Verified public').first()).toBeVisible(); // the address and the named contact
    await expect(page.getByText('Marie Dupont')).toBeVisible();
    await expect(page.getByText('No publicly available HR email found')).toHaveCount(0);
    await shot(page, '08-hr-contact');
    await page.getByRole('button', { name: 'Use as email recipient' }).click();
  });

  await test.step('8. Generate and copy a tailored application email', async () => {
    await page.getByRole('tab', { name: 'Application email' }).click();
    await page.getByRole('button', { name: 'Write a draft' }).click();
    await expect(page.locator('#d-subject')).toHaveValue('Application: Senior Java Developer (m/f/d) (Acme GmbH)');
    await expect(page.locator('#d-body')).toHaveValue(/What matches your posting:/);
    await expect(page.locator('#d-body')).toHaveValue(/My CV and cover letter are attached\./);
    await expect(page.getByText('Nothing is sent from here')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Open in my email app' })).toHaveAttribute('href', /^mailto:careers@acme-corp\.io\?subject=/);
    await shot(page, '09-application-email');

    await page.getByRole('button', { name: 'Copy subject' }).click();
    await expect(page.getByText('Copied: Subject')).toBeVisible();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('Application: Senior Java Developer (m/f/d) (Acme GmbH)');
    await page.getByRole('button', { name: 'Copy message' }).click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toContain('What matches your posting:');
  });

  await test.step('9. Save the offer to the application tracker', async () => {
    await page.getByRole('tab', { name: 'Job description' }).click();
    await page.getByRole('button', { name: 'Save to tracker' }).click();
    await expect(page.getByText('In your tracker: To review')).toBeVisible();

    await page.getByRole('link', { name: 'Applications', exact: true }).click();
    const toReview = page.locator('section.col[aria-label="To review"]');
    await expect(toReview).toContainText('Senior Java Developer (m/f/d)');
    await toReview.locator('select').selectOption({ label: 'Applied' });
    const applied = page.locator('section.col[aria-label="Applied"]');
    await expect(applied).toContainText('Senior Java Developer (m/f/d)');
    await expect(applied).toContainText('Reminder'); // a follow-up reminder was created
    await shot(page, '10-tracker-board');

    await applied.getByRole('link', { name: 'Senior Java Developer (m/f/d)' }).click();
    await expect(page.getByText('To review → Applied')).toBeVisible();
    await shot(page, '11-application-detail');
  });

  await test.step('10. The whole stack ran locally from the documented open-source components', async () => {
    // Everything above used the packaged API, PostgreSQL, the production web build and the mock AI provider.
    const status = await page.request.get('/api/status');
    expect(await status.json()).toMatchObject({ api: 'ok', ai: { provider: 'mock', available: true } });
    await page.getByRole('link', { name: 'Dashboard' }).click();
    await expect(page.getByText('1 tracked')).toBeVisible();
    await shot(page, '12-dashboard');
  });
});

test('a user can export and delete everything', async ({ page }) => {
  const email = `privacy-${Date.now()}@example.org`;
  await page.goto('/register');
  await page.getByLabel('Your name').fill('Pat Privacy');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('correct horse battery');
  await page.getByLabel(/I agree that my CV/).check();
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('heading', { name: 'My CVs' })).toBeVisible();
  await page.getByLabel('CV text').fill('Pat Privacy\npat@example.org\n\nExperience\nDeveloper - Acme\n2020 - 2023\n- Built services\n\nSkills\nJava, Docker');
  await page.locator('#tx-label').fill('Pat CV');
  await page.getByRole('button', { name: 'Add CV' }).last().click();
  await expect(page.getByRole('heading', { name: 'Review extracted data' })).toBeVisible();

  await page.getByRole('link', { name: 'Settings' }).click();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download my data' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe('jobmatcher-data.json');
  const exported = JSON.parse(await (await import('node:fs/promises')).readFile((await file.path())!, 'utf-8'));
  expect(exported.account.email).toBe(email);
  expect(exported.cvs).toHaveLength(1);
  expect(JSON.stringify(exported)).not.toContain('correct horse battery');

  await page.getByLabel('Enter your password to confirm').fill('correct horse battery');
  await page.getByRole('button', { name: 'Delete everything' }).click();
  await expect(page.getByRole('link', { name: 'Create a free account' })).toBeVisible();

  // The account is gone: signing in again fails with the generic message.
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('correct horse battery');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText('Invalid email or password.')).toBeVisible();
});
