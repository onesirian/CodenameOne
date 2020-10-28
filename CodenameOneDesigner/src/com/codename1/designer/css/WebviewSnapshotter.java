/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.codename1.designer.css;

import com.codename1.io.Log;
import com.codename1.processing.Result;
import com.codename1.ui.BrowserComponent;
import com.codename1.ui.CN;
import com.codename1.ui.Image;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 *
 * @author shannah
 */
public class WebviewSnapshotter {
    private int x, y, w, h;
    private BrowserComponent web;
    final LinkedList<Runnable> eventQueue = new LinkedList<Runnable>();
    Thread eventThread;
    Runnable onDone;
    boolean done;
    BufferedImage image;
    Graphics2D imageGraphics;
    
    
    public WebviewSnapshotter(BrowserComponent web) {
        this.web = web;
        
        //this.onDone = onDone;
    }
    
    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y =y;
        this.w = w;
        this.h = h;
    }
    
    private void fireJSSnap(int x, int y, int w, int h) {
        if (!CN.isEdt()) {
            CN.callSerially(()-> {
                fireJSSnap(x, y, w, h);
            });
            return;
        } 
        
        web.execute("window.snapper = window.snapper || {}; "
                + "window.snapper.handleJSSnap = function(scrollX, scrollY, x, y, w, h) {"
                + "  callback.onSuccess(JSON.stringify({scrollX:scrollX, scrollY:scrollY, x:x, y:y, w:w, h:h}));"
                + "}; window.getRectSnapshot("+x+","+y+","+w+","+h+");", res -> {
                    try {
                        Result data = Result.fromContent(res.toString(), Result.JSON);
                        handleJSSnap(
                                data.getAsInteger("scrollX"),
                                data.getAsInteger("scrollY"),
                                data.getAsInteger("x"),
                                data.getAsInteger("y"),
                                data.getAsInteger("w"),
                                data.getAsInteger("h")
                        );
                    } catch (Exception ex) {
                        Log.p("Failed to parse callback in fireJSSnap");
                        Log.e(ex);
                    }
                });
    }
    
    private boolean isEventThread() {
        return Thread.currentThread() == eventThread;
    }
    
    private void runLater(Runnable r) {
        synchronized(eventQueue) {
            eventQueue.add(r);
            eventQueue.notify();
        }
    }
    
    public void log(String msg) {
        System.out.println(msg);
    }
    
    public final void handleJSSnap(int scrollX, int scrollY, int x, int y, int w, int h) {
        //System.out.println("In handleJSSnap before thread check");
        if (!isEventThread()) {
            runLater(()-> {
                handleJSSnap(scrollX, scrollY, x, y, w, h);
            });
            return;
        }
        
        //System.out.println("In handleJSSnap "+scrollX+", "+scrollY+", "+x+","+y+","+w+","+h);
        CN.callSerially(()-> {
            //snapshotParams.
            //WritableImage wi = web.snapshot(snapshotParams, null);
            Image wi = web.captureScreenshot().get();

            BufferedImage img = (BufferedImage)wi.getImage();
            
            runLater(()-> {
                //System.out.println("Getting subImag "+(x-scrollX)+", "+(y-scrollY)+", "+w+", "+h+" for image "+img.getWidth()+", "+img.getHeight());
                int remw = Math.min(w, 320);
                int remh = Math.min(h, 480);
                //remw = Math.min(remw, 320);
                //remh = Math.min(remh, 480);
                BufferedImage img2 = img.getSubimage(20,20, remw, remh);
                //System.out.println("Writing image at "+(x-20)+", "+(y-20)+" with width "+remw+" and height "+remh);
                imageGraphics.drawImage(img2, null, x-20, y-20);
                if (x+remw < this.x+this.w || y+remh < this.y+this.h) {
                    if (x+remw < this.x+this.w) {
                        fireJSSnap(x+remw, y, w, h);
                    } else {
                        fireJSSnap(this.x, y+remh, w, h);
                    }
                } else {
                    fireDone();
                }
            });
            
            //
        });
       
        
        
        
        
    }
    
    public final void fireDone() {
        
        if (!isEventThread()) {
            runLater(()-> {
                fireDone();
            });
            return;
        } 
        //System.out.println("In fireDone()");
        imageGraphics.dispose();
        
        // Now do the fireDone
        if (onDone != null) {
            onDone.run();
        }
        done = true;
    }
    
    public void snapshot(Runnable onDone) {
        if (eventThread != null) {
            throw new RuntimeException("Snapshot event thread already created");
        }
        eventThread = new Thread(()-> {
            while (!done) {
                Runnable evt = null;
                synchronized(eventQueue) {
                    if (eventQueue.isEmpty()) {
                        try {
                            eventQueue.wait();
                        } catch (InterruptedException ex) {
                            Logger.getLogger(WebviewSnapshotter.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }
                    evt = eventQueue.remove();
                }
                evt.run();
            }
        });
        this.onDone = onDone;
        int x = this.x;
        int y = this.y;
        int width = Math.min(this.w, 320);
        int height = Math.min(this.h, 480);
        this.image = new BufferedImage(this.w, this.h, BufferedImage.TYPE_INT_ARGB);
        this.imageGraphics = this.image.createGraphics();
        fireJSSnap(x, y, width, height);
        eventThread.start();
        
    }
    
    public void snapshotSync() {
        Object lock = new Object();
        boolean[] complete = new boolean[1];
        snapshot(()-> {
            complete[0] = true;
            synchronized(lock) {
                lock.notify();
            }
        });
        
        while (!complete[0]) {
            synchronized(lock) {
                try {
                    lock.wait();
                } catch (InterruptedException ex) {
                    Logger.getLogger(WebviewSnapshotter.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }
    
    public BufferedImage getImage() {
        return image;
    }
}
