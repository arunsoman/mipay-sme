import { Directive, Signal, computed, inject } from '@angular/core';
import { PageSelection, WIDGET_CONFIG, WIDGET_CONTEXT, WIDGET_INPUTS, WIDGET_SIZE, WidgetStateRef } from './context';
import { WidgetDataPort } from './port';

/**
 * The base of every MiPay widget component. A manifest's `load` is typed
 * `Promise<Type<MiPayWidget<C>>>`, so registering a component that does not extend this class
 * does not compile (the protected members make the check nominal, not a look-alike shape).
 *
 * What a widget gets, already wired by the host for the context it is placed in:
 *   config   its settings from the layout (defaults filled in), typed C
 *   data     the port - the ONLY way to reach the network (never inject HttpClient)
 *   state    loading / ready / empty / error / forbidden (the port tracks most of it)
 *   context  portal, own org, screen, mode
 *   size     the cell's grid tier and width (structural switches: fewer columns on a phone;
 *            styling stays in @container)
 *   input()  a manifest input, resolved from page selection or context
 *   publish  write a page-selection key this widget declares in `publishes`
 */
@Directive()
export abstract class MiPayWidget<C = Record<string, unknown>> {
  protected readonly config = inject(WIDGET_CONFIG) as unknown as Readonly<C>;
  protected readonly data = inject(WidgetDataPort);
  protected readonly state = inject(WidgetStateRef);
  protected readonly context = inject(WIDGET_CONTEXT);
  protected readonly size = inject(WIDGET_SIZE);
  private readonly inputs = inject(WIDGET_INPUTS);
  private readonly selection = inject(PageSelection, { optional: true });

  /** A manifest input (null until something provides it). */
  protected input<T = unknown>(key: string): Signal<T | null> {
    const s = this.inputs[key];
    if (!s) throw new Error(`input "${key}" is not declared in the widget manifest`);
    return computed(() => (s() ?? null) as T | null);
  }

  protected publish(key: string, value: unknown) {
    this.selection?.set(key, value);
  }
}
