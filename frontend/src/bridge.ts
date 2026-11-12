import type {Boot} from './types';

interface Host {
  postMessage(data: unknown): void;
  postMessageWithAdditionalObjects?(
    data: unknown,
    objects: File[]
  ): void;
  addEventListener(
    type: string,
    listener: (event: {data: any}) => void
  ): void;
}

interface AndroidHost {
  postMessage(message: string): void;
}

declare global {
  interface Window {
    chrome?: {webview?: Host};
    HokAndroid?: AndroidHost;
    __hokAndroidMessage?: (message: string) => void;
  }
}

const pending = new Map<
  string,
  {
    resolve: (value: any) => void;
    reject: (error: Error) => void;
    timer: number;
  }
>();

export const desktop = Boolean(window.chrome?.webview);
export const android = Boolean(window.HokAndroid);

function receive(data: any) {
  const request = pending.get(data?.id);
  if (!request) return;

  clearTimeout(request.timer);
  pending.delete(data.id);

  if (data.ok) {
    request.resolve(data.data);
  } else {
    request.reject(new Error(data.error || 'Android request failed'));
  }
}

window.chrome?.webview?.addEventListener('message', event => {
  receive(event.data);
});

window.__hokAndroidMessage = message => {
  try {
    receive(JSON.parse(message));
  } catch {
    // Ignore invalid messages from the native host.
  }
};

export async function rpc<T = any>(
  method: string,
  payload: unknown = {},
  files?: File[]
): Promise<T> {
  if (!desktop && method === 'boot') {
    if (method === 'boot') {
      const index = await fetch(
        '/assets/catalog/remote-index.seed.json'
      ).then(response => {
        if (!response.ok) throw new Error('Cannot load bundled catalog');
        return response.json();
      });

      return {
        version: '1.3',
        revision: 'bundled-seed',
        heroes: index.records
          .filter((entry: any) => entry.kind === 'hero')
          .map((entry: any) => ({
            id: entry.id,
            name: entry.name,
            portrait: entry.image_url
          })),
        skins: index.records
          .filter((entry: any) => entry.kind === 'skin')
          .map((entry: any) => ({
            skinId: entry.id,
            heroId: entry.hero_id,
            name: entry.name,
            portrait: entry.image_url,
            artAvailable: true
          })),
        artOrigin: '/assets/',
        initialPaths: []
      } as T;
    }

    throw new Error('A supported native host is required.');
  }

  return new Promise((resolve, reject) => {
    const id = crypto.randomUUID();

    const timer = window.setTimeout(() => {
      pending.delete(id);
      reject(new Error('Request timed out'));
    }, 420000);

    pending.set(id, {resolve, reject, timer});

    try {
      if (desktop) {
        const host = window.chrome!.webview!;

        if (files) {
          if (!host.postMessageWithAdditionalObjects) {
            throw new Error(
              'WebView2 file drop is unavailable.'
            );
          }

          host.postMessageWithAdditionalObjects(
            {id, method, payload},
            files
          );
        } else {
          host.postMessage({id, method, payload});
        }
      } else if (android) {
        if (files) {
          throw new Error(
            'Use the Android Open Files picker instead of drag and drop.'
          );
        }

        window.HokAndroid!.postMessage(
          JSON.stringify({id, method, payload})
        );
      } else {
        throw new Error('Native host unavailable.');
      }
    } catch (error) {
      clearTimeout(timer);
      pending.delete(id);
      reject(
        error instanceof Error ? error : new Error(String(error))
      );
    }
  });
}

export const boot = () => rpc<Boot>('boot');

export function placeholder(id: string) {
  let hash = 2166136261;

  for (const byte of new TextEncoder().encode(id)) {
    hash = Math.imul(hash ^ byte, 16777619) >>> 0;
  }

  return [
    'skin-jade-lotus',
    'skin-tidal-ring',
    'skin-cloud-feather',
    'skin-faceted-seal'
  ][hash % 4];
}
