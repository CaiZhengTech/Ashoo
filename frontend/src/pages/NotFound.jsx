import { Link } from 'react-router-dom';
import { Card, Button } from '../components/ui';

export default function NotFound() {
  return (
    <Card className="card-pad mx-auto max-w-md text-center">
      <div className="mb-2 text-3xl">🌬️</div>
      <h1 className="text-xl font-bold text-ink-800">Page not found</h1>
      <p className="mt-1 text-sm text-ink-500">
        That route drifted away on the breeze. Let's get you back.
      </p>
      <Link to="/" className="mt-4 inline-block">
        <Button>Back to dashboard</Button>
      </Link>
    </Card>
  );
}
