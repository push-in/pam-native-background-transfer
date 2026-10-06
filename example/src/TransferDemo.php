<?php

declare(strict_types=1);

namespace App;

use Pam\Native\BackgroundTransfer\Backoff;
use Pam\Native\BackgroundTransfer\BackgroundTransfer;
use Pam\Native\BackgroundTransfer\HttpStep;
use Pam\Native\BackgroundTransfer\Multipart;
use Pam\Native\BackgroundTransfer\NetworkRequirement;
use Pam\Native\BackgroundTransfer\TransferHandle;
use Pam\Native\BackgroundTransfer\TransferNotification;
use Pam\Native\BackgroundTransfer\TransferSnapshot;
use Pam\Native\BackgroundTransfer\TransferWatch;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Style;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

final class TransferDemo extends Component
{
    private const string TAG = 'demo';
    private const string SOURCE = 'https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3';
    private const string FILE = 'downloads/sample.mp3';

    /** @var array<string, TransferSnapshot> */
    private array $transfers = [];

    /** @var array<string, TransferWatch> */
    private array $watches = [];

    private string $error = '';

    public function boot(): void
    {
        BackgroundTransfer::prune(olderThanDays: 7);
        // Re-attach after a relaunch: the native store survives the process.
        BackgroundTransfer::all(function (array $snapshots): void {
            foreach ($snapshots as $snapshot) {
                $this->transfers[$snapshot->identifier] = $snapshot;
                if (!$snapshot->finished()) {
                    $this->follow($snapshot->identifier);
                }
            }
        }, tag: self::TAG);
    }

    public function render(): Element
    {
        $rows = [];
        foreach (array_reverse($this->transfers) as $snapshot) {
            $rows[] = Text::make(sprintf(
                '%s %s · %s · %d%%%s',
                $snapshot->kind->name,
                substr($snapshot->identifier, 0, 8),
                $snapshot->state->name,
                (int) round($snapshot->progress() * 100),
                $snapshot->response !== null ? ' · HTTP '.$snapshot->response->statusCode : '',
            ));
        }

        return Screen::make(
            SafeAreaView::make(
                Column::make(
                    Text::make('Background transfers')->style(new Style(fontSize: 24, fontWeight: 700)),
                    Button::make('Download sample')->onPress($this->download(...)),
                    Button::make('Upload it, then post JSON')->onPress($this->upload(...)),
                    Button::make('Cancel running')->onPress($this->cancelRunning(...)),
                    $this->error !== '' ? Text::make('Error: '.$this->error) : null,
                    ...$rows,
                )->style(new Style(flexGrow: 1, padding: 24, gap: 12)),
            ),
        );
    }

    public function download(): void
    {
        BackgroundTransfer::download(self::SOURCE)
            ->to(self::FILE)
            ->notification(TransferNotification::make('Downloading sample')->progress()->completed('Sample downloaded'))
            ->retry(3, Backoff::Exponential)
            ->unique('demo-download')
            ->tag(self::TAG)
            ->dispatch($this->started(...), $this->dispatchFailed(...));
    }

    public function upload(): void
    {
        BackgroundTransfer::upload('https://httpbin.org/post')
            ->multipart(fn (Multipart $m) => $m
                ->file('file', self::FILE, 'audio/mpeg', 'sample.mp3')
                ->field('caption', 'Uploaded by {{transfer.tag}}')         // templated
                ->field('note', 'Literal {{not a template}}', template: false))
            ->network(NetworkRequirement::Connected)
            ->retry(2, Backoff::Linear, 10)
            ->then(HttpStep::post('https://httpbin.org/anything')
                ->json(['uploaded_from' => '{{response.headers.Host}}', 'transfer' => '{{transfer.id}}']))
            ->tag(self::TAG)
            ->dispatch($this->started(...), $this->dispatchFailed(...));
    }

    public function cancelRunning(): void
    {
        foreach ($this->transfers as $id => $snapshot) {
            if (!$snapshot->finished()) {
                BackgroundTransfer::cancel($id);
            }
        }
    }

    private function started(TransferHandle $handle): void
    {
        $this->error = '';
        $this->follow($handle->id);
    }

    private function dispatchFailed(string $message): void
    {
        // For example "does not exist" when uploading before the download finished.
        $this->error = $message;
    }

    private function follow(string $id): void
    {
        if (isset($this->watches[$id])) {
            return;
        }
        $this->watches[$id] = BackgroundTransfer::watch($id, function (TransferSnapshot $snapshot) use ($id): void {
            $this->transfers[$id] = $snapshot;
            if ($snapshot->finished()) {
                $this->watches[$id]->stop();
                unset($this->watches[$id]);
            }
        });
    }
}
