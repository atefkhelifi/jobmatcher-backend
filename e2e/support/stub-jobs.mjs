// A local stand-in for the Arbeitnow job board API, so the end-to-end test is deterministic and needs no internet.
// Same JSON shape as https://www.arbeitnow.com/api/job-board-api. All companies and people are invented.
import { createServer } from 'node:http';

const now = Math.floor(Date.now() / 1000);

const jobs = [
  {
    slug: 'senior-java-developer-berlin-9001',
    company_name: 'Acme GmbH',
    title: 'Senior Java Developer (m/f/d)',
    description: `<p>We build logistics software and need a Senior Java Developer to join our team in Berlin.</p>
      <h3>Requirements</h3><ul><li>5+ years of experience with Java</li><li>Strong knowledge of Spring Boot and PostgreSQL</li><li>Fluent English</li></ul>
      <h3>Nice to have</h3><ul><li>Kafka</li><li>Kubernetes</li></ul>
      <p>Send your application to careers@acme-corp.io. Contact: Marie Dupont, Talent Acquisition Manager.</p>`,
    remote: false,
    url: 'https://www.arbeitnow.com/jobs/companies/acme-gmbh/senior-java-developer-berlin-9001',
    tags: ['Technology'],
    job_types: ['full_time'],
    location: 'Berlin',
    created_at: now - 3600,
  },
  {
    slug: 'developpeur-java-spring-lyon-9002',
    company_name: 'Société Exemple',
    title: 'Développeur Java Spring (H/F)',
    description: `<p>Nous recherchons un développeur pour renforcer notre équipe à Lyon. Vous participerez à la conception des services de notre plateforme
      et vous travaillerez avec les équipes produit dans un environnement agile.</p>
      <h3>Profil recherché</h3><ul><li>Maîtrise de Java et de Spring Boot</li><li>3 ans d'expérience minimum</li><li>Français courant</li></ul>
      <h3>Atouts</h3><ul><li>Angular est un plus</li></ul>
      <p>Pour postuler, utilisez le formulaire sur notre site.</p>`,
    remote: false,
    url: 'https://www.arbeitnow.com/jobs/companies/societe-exemple/developpeur-java-spring-lyon-9002',
    tags: [],
    job_types: [],
    location: 'Lyon',
    created_at: now - 7200,
  },
  {
    slug: 'pastry-chef-nantes-9003',
    company_name: 'Boulangerie Soleil',
    title: 'Pastry Chef',
    description: '<p>We are looking for a pastry chef to prepare croissants and tarts every morning. Early starts required.</p>',
    remote: false,
    url: 'https://www.arbeitnow.com/jobs/companies/boulangerie-soleil/pastry-chef-nantes-9003',
    tags: [],
    job_types: [],
    location: 'Nantes',
    created_at: now - 10800,
  },
];

createServer((req, res) => {
  if (req.url?.startsWith('/health')) {
    res.writeHead(200, { 'Content-Type': 'text/plain' }).end('ok');
  } else if (req.url?.startsWith('/api/job-board-api')) {
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(
      JSON.stringify({ data: jobs, links: { first: null, next: null }, meta: { current_page: 1 } }),
    );
  } else if (req.url === '/robots.txt') {
    res.writeHead(200, { 'Content-Type': 'text/plain' }).end('User-agent: *\nDisallow:\n');
  } else {
    res.writeHead(404).end();
  }
}).listen(4010, '127.0.0.1', () => console.log('stub job source on :4010'));
