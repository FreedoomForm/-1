export default function handler(req: Request): Response {
  return new Response(JSON.stringify({ pong: true, ts: Date.now() }), {
    headers: { 'Content-Type': 'application/json' }
  });
}
