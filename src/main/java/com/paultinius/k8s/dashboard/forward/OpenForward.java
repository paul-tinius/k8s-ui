package com.paultinius.k8s.dashboard.forward;

import com.paultinius.k8s.dashboard.model.ForwardView;

public final class OpenForward {

    private final ForwardView view;
    private final Runnable closeAction;

    public OpenForward(ForwardView view, Runnable closeAction) {
        this.view = view;
        this.closeAction = closeAction == null ? () -> { } : closeAction;
    }

    public ForwardView view() {
        return view;
    }

    public void close() {
        try {
            closeAction.run();
        } catch (RuntimeException ignored) {
            // The local socket may already be closed.
        }
    }
}
